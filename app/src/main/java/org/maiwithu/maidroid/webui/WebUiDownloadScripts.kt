package org.maiwithu.maidroid.webui

import kotlinx.serialization.json.JsonPrimitive

/**
 * 网页下载用的注入脚本：把 blob:、data: 地址背后的文件内容经 JS 桥（WebUiDownloads 注册的 window.MaiDroidDownloads）送给宿主。
 * 拦截 HTML 时随 WebUiInjectedScripts.pageBootstrap 插在页面脚本之前；HTML 没拦截到的页面（NapCat 管理页由 service worker 接管）
 * 由 WebUiWebViewClient 在页面加载完时补装，没经过点击拦截的 blob 下载再由 WebUiDownloads 补装一次。
 *
 * 为什么要在网页里拦：WebUI 的导出按钮都是“生成 Blob -> createObjectURL -> 建一个带 download 的链接 -> click -> 马上 revokeObjectURL”。
 * 宿主只能在 onDownloadStart 里拿到 blob 地址，这时地址往往已经被 revoke，内容也只活在网页内存里，宿主读不到；
 * 而且 onDownloadStart 不带 download 属性里的文件名。所以在点击的那一刻就在网页里读出内容和名字。
 */
internal object WebUiDownloadScripts {
    /** JS 桥在网页里的名字。 */
    const val BRIDGE_NAME = "MaiDroidDownloads"

    // 每块 512 KB，base64 后约 700 KB 一个字符串，经 JS 桥传递不卡页面。
    private const val CHUNK_BYTES = 512 * 1024

    // 网页 revoke 之后再记一分钟：宿主经 onDownloadStart 回头来要内容时，地址可能已经被 revoke 了。
    private const val FORGET_REVOKED_AFTER_MS = 60_000

    /**
     * 安装下载拦截，重复执行只装一次；页面里没有 JS 桥（别的 WebView）时什么也不做。
     * 执行顺序：包一层 URL.createObjectURL，记住网页生成的每个 Blob -> 拦截 a[download] 指向 blob:/data: 的点击
     * （脚本里调 a.click() 和用户真的点链接两种）-> 读出 Blob，分块 base64 送给宿主 begin/append/finish -> 宿主弹“另存为”。
     * http 地址的下载链接（数据迁移导出的 zip）不拦，照常交给 WebView，由宿主的 onDownloadStart 重新请求。
     */
    val installScript: String = """
        (function() {
          try {
            if (window.__maidroidDownloads) return;
            var bridge = window.$BRIDGE_NAME;
            if (!bridge) return;

            // 记住网页生成的 Blob：网页点完下载链接常常马上 revoke，地址失效后就再也读不到内容了
            var blobs = new Map();
            var nativeCreateObjectURL = URL.createObjectURL;
            var nativeRevokeObjectURL = URL.revokeObjectURL;
            URL.createObjectURL = function(object) {
              var url = nativeCreateObjectURL.apply(URL, arguments);
              if (object instanceof Blob) blobs.set(url, object);
              return url;
            };
            URL.revokeObjectURL = function(url) {
              setTimeout(function() { blobs.delete(url); }, $FORGET_REVOKED_AFTER_MS);
              return nativeRevokeObjectURL.apply(URL, arguments);
            };

            var describe = function(error) {
              return String((error && error.message) || error || 'unknown');
            };

            // 读一块内容并去掉 data URL 的前缀，只留 base64
            var readChunk = function(blob) {
              return new Promise(function(resolve, reject) {
                var reader = new FileReader();
                reader.onload = function() {
                  var result = String(reader.result || '');
                  resolve(result.slice(result.indexOf(',') + 1));
                };
                reader.onerror = function() { reject(reader.error); };
                reader.readAsDataURL(blob);
              });
            };

            // 报上名字、类型和大小 -> 一块块送 -> 收尾；宿主拒收（太大）或中途出错时停下
            var saveBlob = function(blob, name) {
              var id = bridge.begin(String(name || ''), String(blob.type || ''), blob.size);
              if (!id) return Promise.resolve();
              var offset = 0;
              var next = function() {
                if (offset >= blob.size) {
                  bridge.finish(id);
                  return;
                }
                var end = Math.min(offset + $CHUNK_BYTES, blob.size);
                return readChunk(blob.slice(offset, end)).then(function(data) {
                  if (!bridge.append(id, data)) return;
                  offset = end;
                  return next();
                });
              };
              return Promise.resolve().then(next).catch(function(error) {
                bridge.fail(id, describe(error));
              });
            };

            // 记住的 Blob 优先；没记住（在拦截装上之前生成的）就趁地址还有效时现读
            var saveUrl = function(url, name) {
              var blob = blobs.get(url);
              var source = blob
                ? Promise.resolve(blob)
                : fetch(url).then(function(response) { return response.blob(); });
              return source.then(function(found) { return saveBlob(found, name); }, function(error) {
                bridge.fail('', describe(error));
              });
            };

            var inlineHref = function(anchor) {
              if (!anchor || !anchor.hasAttribute || !anchor.hasAttribute('download')) return null;
              var href = String(anchor.href || '');
              return href.indexOf('blob:') === 0 || href.indexOf('data:') === 0 ? href : null;
            };

            // 脚本里 a.click()（链接常常都没放进页面）：直接接手，不再派发点击
            var nativeClick = HTMLAnchorElement.prototype.click;
            HTMLAnchorElement.prototype.click = function() {
              var href = inlineHref(this);
              if (!href) return nativeClick.apply(this, arguments);
              saveUrl(href, this.getAttribute('download'));
            };

            // 用户真的点了页面里的下载链接：在捕获阶段最先接手，阻止 WebView 自己去下载
            window.addEventListener('click', function(event) {
              var target = event.target;
              var anchor = target && target.closest ? target.closest('a') : null;
              var href = inlineHref(anchor);
              if (!href) return;
              event.preventDefault();
              saveUrl(href, anchor.getAttribute('download'));
            }, true);

            window.__maidroidDownloads = { saveUrl: saveUrl };
          } catch (error) {}
        })();
    """.trimIndent()

    /** 拦截 HTML 时插进页面的脚本标签。 */
    val bootstrapTag: String = "<script>\n$installScript\n</script>"

    /**
     * onDownloadStart 收到 blob:/data: 地址时执行（没经过上面的点击拦截，比如网页派发合成点击事件）：
     * 先补装拦截（已装过就跳过），再按地址找内容送给宿主。fileName 是宿主按类型猜的名字。
     */
    fun saveUrlScript(url: String, fileName: String): String =
        installScript + "\n" +
            "window.__maidroidDownloads && " +
            "window.__maidroidDownloads.saveUrl(${JsonPrimitive(url)}, ${JsonPrimitive(fileName)});"
}
