package org.maiwithu.maidroid.webui

/**
 * 网页底色上报。横屏时主页的毛玻璃侧栏悬浮在页面左侧，WebUI 页签的网页让出侧栏的宽度，
 * 让出的那一条要涂成网页自己的底色，侧栏底下透出的才是网页的颜色，而不是隔着一条黑边。
 *
 * 页面加载完 -> 宿主注入 watchScript：取 body 的背景色（透明时取 html 的），画到 1 像素画布上换成 sRGB 字节 ->
 * console 打一行“前缀 + r,g,b,a” -> WebUiChromeClient 认出前缀，交给 parseReport 解析成 ARGB。
 * 之后网页换主题（html、body 的属性或 head 里的样式表变了）就再报一次；颜色没变不重复报。
 */
internal object WebUiPageBackground {
    const val REPORT_PREFIX = "__maidroid_page_background__:"

    /**
     * 盯网页底色的脚本，同一个页面重复注入只生效一次。
     * 经画布换算是因为 getComputedStyle 可能给出 rgb、hsl 以外的写法（如 oklch），宿主只解析四个整数。
     * WebUI 换主题时底色常带过渡动画：一有变动先报一次，400ms 后过渡走完再报一次最终颜色。
     */
    val watchScript = """
        (function() {
          if (window.__maidroidPageBackgroundWatch) return;
          window.__maidroidPageBackgroundWatch = true;
          var canvas = document.createElement('canvas');
          canvas.width = 1;
          canvas.height = 1;
          var context = canvas.getContext('2d', { willReadFrequently: true });
          var last = '';
          var timer = 0;
          function toBytes(color) {
            context.clearRect(0, 0, 1, 1);
            context.fillStyle = 'rgba(0, 0, 0, 0)';
            context.fillStyle = color;
            context.fillRect(0, 0, 1, 1);
            var data = context.getImageData(0, 0, 1, 1).data;
            return [data[0], data[1], data[2], data[3]];
          }
          function report() {
            var bytes = document.body ? toBytes(getComputedStyle(document.body).backgroundColor) : [0, 0, 0, 0];
            if (bytes[3] === 0) bytes = toBytes(getComputedStyle(document.documentElement).backgroundColor);
            var text = bytes.join(',');
            if (text === last) return;
            last = text;
            console.log('$REPORT_PREFIX' + text);
          }
          function schedule() {
            report();
            clearTimeout(timer);
            timer = setTimeout(report, 400);
          }
          var observer = new MutationObserver(schedule);
          observer.observe(document.documentElement, { attributes: true });
          if (document.body) observer.observe(document.body, { attributes: true });
          if (document.head) observer.observe(document.head, { childList: true, subtree: true, characterData: true });
          window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', schedule);
          report();
        })();
    """.trimIndent()

    /**
     * 把 console 里的一行解析成 ARGB；不是底色上报（前缀不对）或格式不对时返回 null，
     * 调用方据此决定这行是吞掉还是照常写进 logcat。
     */
    fun parseReport(message: String): Int? {
        if (!message.startsWith(REPORT_PREFIX)) return null
        val bytes = message.removePrefix(REPORT_PREFIX).split(',').map { part ->
            part.trim().toIntOrNull()?.takeIf { it in 0..255 } ?: return null
        }
        if (bytes.size != 4) return null
        val (red, green, blue, alpha) = bytes
        return (alpha shl 24) or (red shl 16) or (green shl 8) or blue
    }
}
