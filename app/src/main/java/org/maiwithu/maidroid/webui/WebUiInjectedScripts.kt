package org.maiwithu.maidroid.webui

/**
 * 注入 MaiBot WebUI 页面的脚本。
 *
 * - pageBootstrap：拦截 HTML 时插在页面第一个脚本之前，先于页面自己的脚本执行（主题 + vh 兜底）。
 * - viewportPatch：页面加载完后经 evaluateJavascript 执行（视口高度与导航栏留白）。
 */
internal object WebUiInjectedScripts {
    private const val THEME_MODE = "dark"
    private const val DASHBOARD_STYLE = "future-retro"
    private const val VIEWPORT_PATCH_STYLE_ID = "maidroid-webview-viewport-fix"

    /**
     * 第一次打开 WebUI 时默认用深色、“未来复古”仪表盘风格，与 APP 的深色界面一致。
     * 触发条件：页面头部脚本执行时，WebUI 自己存外观设置的两个键还没有值。
     * 原因：以前每次加载都直接覆盖这两个键，用户在 WebUI“外观设置”里换的主题，下次启动 APP 又被改回“未来复古”；
     * 现在只补空缺，用户选过的主题原样保留。键名与 WebUI 前端一致（maibot-theme-mode、maibot-theme-dashboard-style）。
     */
    private val themeBootstrapScript = """
        <script>
        (function() {
          try {
            if (localStorage.getItem('maibot-theme-mode') === null) {
              localStorage.setItem('maibot-theme-mode', '$THEME_MODE');
            }
            if (localStorage.getItem('maibot-theme-dashboard-style') === null) {
              localStorage.setItem('maibot-theme-dashboard-style', '$DASHBOARD_STYLE');
            }
          } catch (error) {}
        })();
        </script>
    """.trimIndent()

    /**
     * 兜底：WebView 主框架里 vh/dvh/svh/lvh 恒为 0 时，改写样式表让页面仍能排版。
     * 已知成因是宿主给 WebView 的布局高度是 WRAP_CONTENT（Compose 的 AndroidView 默认如此），
     * WebUI 页签（ui/home/WebUiTab）已显式设为铺满父视图，正常情况下这里探针量得出高度，脚本不会启用；
     * 保留它是为了应对以后换宿主布局、或别的宿主再次踩到同一个坑。
     * 触发条件：页面头部脚本执行时，用探针元素量到 100vh 的高度小于 1px。
     * 执行顺序：先把可视视口高度的 1% 写进 --maidroid-vh -> 再把已加载样式表里所有 vh 类单位
     * 改写成 calc(var(--maidroid-vh) * N) -> 之后监听新插入的样式表和视口变化继续补写。
     * 原因：新版 MaiBot 控制台外层容器用 h-[100dvh]，vh 为 0 时整页高度塌成 0，页面内容存在但画不出来。
     * 视口本身正常的设备探针量得出高度，脚本直接退出，不改动任何样式。
     */
    private val viewportUnitFixScript = """
        <script>
        (function() {
          try {
            // 探针：量 100vh 是否真的有高度，有高度说明这台 WebView 的 vh 正常，无需修补
            var probe = document.createElement('div');
            probe.style.cssText = 'position:absolute;left:-9999px;top:0;width:1px;height:100vh;visibility:hidden;pointer-events:none';
            document.documentElement.appendChild(probe);
            var measured = probe.getBoundingClientRect().height;
            probe.parentNode.removeChild(probe);
            if (measured >= 1 || window.__maidroidVhFixInstalled) return;
            window.__maidroidVhFixInstalled = true;

            // 把可视视口高度的 1% 写进根元素变量，键盘弹出等视口变化时同步更新
            var rootStyle = document.documentElement.style;
            var applyUnit = function() {
              var height = Math.round(
                (window.visualViewport && window.visualViewport.height) ||
                window.innerHeight ||
                document.documentElement.clientHeight ||
                0
              );
              if (height > 0) rootStyle.setProperty('--maidroid-vh', (height / 100) + 'px');
            };
            applyUnit();
            window.addEventListener('resize', applyUnit, { passive: true });
            if (window.visualViewport) {
              window.visualViewport.addEventListener('resize', applyUnit, { passive: true });
            }

            // 逐条改写样式表：只改声明值，不碰选择器（Tailwind 类名里也带 100dvh 字样）
            var hasUnit = /(?:^|[^\w-])-?\d*\.?\d+(?:dvh|svh|lvh|vh)\b/;
            var unitPattern = /(-?\d*\.?\d+)(?:dvh|svh|lvh|vh)\b/g;
            var toCalc = function(match, amount) {
              return 'calc(var(--maidroid-vh, 1px) * ' + amount + ')';
            };
            var seen = new WeakSet();
            var walk = function(rules) {
              for (var i = 0; i < rules.length; i++) {
                var rule = rules[i];
                var style = rule.style;
                if (style) {
                  for (var j = 0; j < style.length; j++) {
                    var name = style[j];
                    var value = style.getPropertyValue(name);
                    if (hasUnit.test(value)) {
                      style.setProperty(name, value.replace(unitPattern, toCalc), style.getPropertyPriority(name));
                    }
                  }
                }
                if (rule.cssRules) walk(rule.cssRules);
              }
            };
            var scan = function() {
              for (var i = 0; i < document.styleSheets.length; i++) {
                var sheet = document.styleSheets[i];
                if (seen.has(sheet)) continue;
                seen.add(sheet);
                try { walk(sheet.cssRules); } catch (error) {}
              }
            };

            // 样式表是异步加载的：link 加载完成、或有新的 style/link 插入时再扫一遍
            document.addEventListener('load', function(event) {
              if (event.target && event.target.tagName === 'LINK') scan();
            }, true);
            var scheduled = false;
            new MutationObserver(function() {
              if (scheduled) return;
              scheduled = true;
              requestAnimationFrame(function() { scheduled = false; scan(); });
            }).observe(document.documentElement, { childList: true, subtree: true });
            scan();
          } catch (error) {}
        })();
        </script>
    """.trimIndent()

    /** 主题脚本 + vh 兜底脚本，拼在一起注入 HTML。 */
    fun pageBootstrap(): String = themeBootstrapScript + "\n" + viewportUnitFixScript

    /**
     * 页面加载完后执行的视口修补：把视口高度和导航栏占高写进 CSS 变量，
     * 强制 html/body/#root、全屏容器、对话框按真实视口高度排版，并给会被导航栏挡住的滚动区补底部留白；
     * 之后监听尺寸变化和 DOM 变化持续重算。返回值是一段 JSON，记录探针量到的 vh 和最终用的高度，打进日志方便排查。
     */
    fun viewportPatch(reservedBottomCssPx: Int): String {
        val safeReservedBottom = reservedBottomCssPx.coerceAtLeast(0)
        return """
            (() => {
              const reservedBottom = $safeReservedBottom;
              const styleId = '$VIEWPORT_PATCH_STYLE_ID';

              const readViewportHeight = () => {
                const visualHeight = window.visualViewport && window.visualViewport.height;
                return Math.max(
                  1,
                  Math.round(
                    visualHeight ||
                    window.innerHeight ||
                    document.documentElement.clientHeight ||
                    document.body.clientHeight ||
                    1
                  )
                );
              };

              const ensureStyle = () => {
                let style = document.getElementById(styleId);
                if (!style) {
                  style = document.createElement('style');
                  style.id = styleId;
                  document.head.appendChild(style);
                }

                style.textContent = [
                  'html,body,#root{width:100%!important;height:var(--maidroid-webview-height)!important;min-height:var(--maidroid-webview-height)!important;overflow:auto!important;overscroll-behavior:none!important}',
                  '.min-h-screen{min-height:var(--maidroid-webview-height)!important}',
                  '.h-screen{height:var(--maidroid-webview-height)!important}',
                  '[data-maidroid-scroll-safe-area="true"]{scroll-padding-bottom:calc(var(--maidroid-webview-reserved-bottom) + 1rem)!important;padding-bottom:calc(var(--maidroid-original-padding-bottom, 0px) + var(--maidroid-webview-reserved-bottom) + 1rem)!important;box-sizing:border-box!important}',
                  '[class~="min-h-screen"][class~="overflow-y-auto"],[class~="h-screen"][class~="overflow-y-auto"],[class~="overflow-y-auto"],[class~="overflow-auto"],[class~="overscroll-contain"]{max-height:var(--maidroid-webview-height)!important;overflow-y:auto!important;-webkit-overflow-scrolling:touch!important}',
                  '[class~="min-h-screen"][class~="items-center"][class~="justify-center"][class~="overflow-y-auto"],[class~="h-screen"][class~="items-center"][class~="justify-center"][class~="overflow-y-auto"]{justify-content:flex-start!important}',
                  // 对话框类容器只把最大高度限制在视口减 1rem，不再减导航栏高度：
                  // 贴底的内边距已经由样式表里的安全区改写避开导航栏，这里再减一次会让对话框底部多出一段空白。
                  '[role="dialog"],[data-radix-dialog-content],[class*="DialogContent"],[class*="dialog-content"],[class*="modal-content"],[class*="drawer"],[class*="Drawer"],[class*="sidebar"],[class*="Sidebar"]{max-height:calc(var(--maidroid-webview-height) - 1rem)!important;overflow-y:auto!important;-webkit-overflow-scrolling:touch!important}',
                  '[class~="max-h-screen"]{max-height:calc(var(--maidroid-webview-height) - var(--maidroid-webview-reserved-bottom) - 1rem)!important}'
                ].join('\n');
              };

              const scrollCandidatePattern = /scroll|overscroll|drawer|sidebar|sheet|dialog|modal|menu|nav|radix/i;

              const markScrollSafeAreas = (viewportHeight) => {
                // 上游移动端底栏在场时，内容区底部留白已由上游按底栏实际位置算好（底栏已随安全区上移到导航栏之上），
                // 这里不再叠加启发式留白；底栏隐藏（聊天页等）时才由这里兜底给滚动区让出导航栏高度。
                const upstreamDockPresent = !!document.querySelector('[data-mobile-tabbar="true"]');
                const scrollPaddingActive = reservedBottom > 0 && !upstreamDockPresent;
                const candidates = [
                  document.scrollingElement,
                  document.documentElement,
                  document.body,
                  document.getElementById('root'),
                  ...document.querySelectorAll('body *')
                ].filter(Boolean);

                candidates.forEach((element) => {
                  const computed = window.getComputedStyle(element);
                  const rect = element.getBoundingClientRect();
                  const className = typeof element.className === 'string' ? element.className : '';
                  const id = element.id || '';
                  const overflowY = computed.overflowY || computed.overflow;
                  const canScroll = element.scrollHeight > element.clientHeight + 12;
                  const scrollStyled = overflowY === 'auto' || overflowY === 'scroll' || overflowY === 'overlay';
                  const viewportSized = rect.height >= viewportHeight * 0.45 || element === document.scrollingElement;
                  const importantSurface =
                    element === document.scrollingElement ||
                    element === document.body ||
                    element.id === 'root' ||
                    computed.position === 'fixed' ||
                    scrollCandidatePattern.test(className + ' ' + id);
                  const shouldMark = scrollPaddingActive && canScroll && (scrollStyled || viewportSized || importantSurface);

                  if (shouldMark) {
                    if (!element.hasAttribute('data-maidroid-scroll-safe-area')) {
                      element.style.setProperty(
                        '--maidroid-original-padding-bottom',
                        computed.paddingBottom || '0px'
                      );
                    }
                    element.setAttribute('data-maidroid-scroll-safe-area', 'true');
                  } else if (element.hasAttribute('data-maidroid-scroll-safe-area')) {
                    element.removeAttribute('data-maidroid-scroll-safe-area');
                    element.style.removeProperty('--maidroid-original-padding-bottom');
                  }
                });
              };

              const apply = () => {
                const viewportHeight = readViewportHeight();
                document.documentElement.style.setProperty('--maidroid-webview-height', viewportHeight + 'px');
                document.documentElement.style.setProperty('--maidroid-webview-reserved-bottom', reservedBottom + 'px');
                ensureStyle();
                markScrollSafeAreas(viewportHeight);
                return viewportHeight;
              };

              if (!window.__maidroidViewportPatchInstalled) {
                window.__maidroidViewportPatchInstalled = true;
                window.addEventListener('resize', apply, { passive: true });
                window.addEventListener('orientationchange', () => setTimeout(apply, 50), { passive: true });
                if (window.visualViewport) {
                  window.visualViewport.addEventListener('resize', apply, { passive: true });
                  window.visualViewport.addEventListener('scroll', apply, { passive: true });
                }
                window.__maidroidViewportPatchObserver = new MutationObserver(() => {
                  window.clearTimeout(window.__maidroidViewportPatchTimer);
                  window.__maidroidViewportPatchTimer = window.setTimeout(apply, 50);
                });
                if (document.body) {
                  window.__maidroidViewportPatchObserver.observe(document.body, {
                    childList: true,
                    subtree: true,
                    attributes: true,
                    attributeFilter: ['class', 'style', 'open', 'data-state']
                  });
                }
              }

              const probe = document.createElement('div');
              probe.style.cssText = 'position:absolute;left:-9999px;top:-9999px;width:1px;height:100vh;pointer-events:none';
              document.body.appendChild(probe);
              const measuredVh = probe.getBoundingClientRect().height;
              probe.remove();

              const viewportHeight = apply();
              window.dispatchEvent(new Event('resize'));
              return JSON.stringify({
                patched: true,
                vh: measuredVh,
                viewportHeight,
                reservedBottom
              });
            })()
        """.trimIndent()
    }
}
