(function() {
  if (window.__maidroidBridgeLoaded) return;
  window.__maidroidBridgeLoaded = true;

  var VIEWPORT_STYLE_ID = 'maidroid-webview-viewport-fix';
  var NATIVE_APP = 'maidroid';
  var BLOB_REQ_EVENT = '__maidroid_blob_req';
  var BLOB_RES_EVENT = '__maidroid_blob_res';
  var CHUNK_BYTES = 512 * 1024;
  var FORGET_REVOKED_MS = 60000;

  // 1. 首次加载补全跟随系统与未来复古主题默认值，不覆盖用户已主动修改过的设置。
  try {
    if (localStorage.getItem('maibot-theme-mode') === null) {
      localStorage.setItem('maibot-theme-mode', 'system');
    }
    if (localStorage.getItem('maibot-theme-dashboard-style') === null) {
      localStorage.setItem('maibot-theme-dashboard-style', 'future-retro');
    }
  } catch (err) {}

  // 2. 在页面主世界安装 Blob / data: URL 下载拦截（document_start 同步执行，早于页面脚本）。
  function injectPageDownloadHook() {
    if (document.getElementById('__maidroid_page_download_hook')) return;
    var root = document.head || document.documentElement;
    if (!root) return;
    var hookScript = document.createElement('script');
    hookScript.id = '__maidroid_page_download_hook';
    hookScript.textContent = '(' + function(reqEventName, resEventName, chunkBytes, forgetMs) {
      if (window.__maidroidDownloadsHooked) return;
      window.__maidroidDownloadsHooked = true;

      var nextSeq = 1;
      var pendingReplies = new Map();
      window.addEventListener(resEventName, function(ev) {
        try {
          var payload = JSON.parse(String(ev.detail || '{}'));
          var resolver = pendingReplies.get(payload.seq);
          if (resolver) {
            pendingReplies.delete(payload.seq);
            resolver(payload);
          }
        } catch (err) {}
      });

      function callNative(msg) {
        return new Promise(function(resolve) {
          var seq = nextSeq++;
          msg.seq = seq;
          pendingReplies.set(seq, resolve);
          window.dispatchEvent(new CustomEvent(reqEventName, { detail: JSON.stringify(msg) }));
        });
      }

      var blobs = new Map();
      var nativeCreate = URL.createObjectURL;
      var nativeRevoke = URL.revokeObjectURL;
      URL.createObjectURL = function(obj) {
        var url = nativeCreate.apply(URL, arguments);
        if (obj instanceof Blob) blobs.set(url, obj);
        return url;
      };
      URL.revokeObjectURL = function(url) {
        setTimeout(function() { blobs.delete(url); }, forgetMs);
        return nativeRevoke.apply(URL, arguments);
      };

      function readChunkBase64(blob) {
        return new Promise(function(resolve, reject) {
          var reader = new FileReader();
          reader.onload = function() {
            var str = String(reader.result || '');
            resolve(str.slice(str.indexOf(',') + 1));
          };
          reader.onerror = function() { reject(reader.error || new Error('FileReader error')); };
          reader.readAsDataURL(blob);
        });
      }

      async function saveBlob(blob, name) {
        var beginReply = await callNative({
          type: 'blobBegin',
          fileName: String(name || ''),
          mimeType: String(blob.type || ''),
          sizeBytes: Number(blob.size || 0)
        });
        var id = beginReply && beginReply.id ? String(beginReply.id) : '';
        if (!id) return;
        try {
          var offset = 0;
          var total = Number(blob.size || 0);
          while (offset < total) {
            var end = Math.min(offset + chunkBytes, total);
            var b64 = await readChunkBase64(blob.slice(offset, end));
            var appendReply = await callNative({
              type: 'blobAppend',
              id: id,
              base64: b64
            });
            if (!appendReply || !appendReply.ok) return;
            offset = end;
          }
          await callNative({ type: 'blobFinish', id: id });
        } catch (err) {
          await callNative({
            type: 'blobFail',
            id: id,
            message: String((err && err.message) || err || 'unknown')
          });
        }
      }

      function saveUrl(url, name) {
        var blob = blobs.get(url);
        var source = blob
          ? Promise.resolve(blob)
          : fetch(url).then(function(r) { return r.blob(); });
        source.then(
          function(found) { return saveBlob(found, name); },
          function(err) {
            return callNative({
              type: 'blobFail',
              id: '',
              message: String((err && err.message) || err || 'fetch failed')
            });
          }
        );
      }

      function inlineHref(anchor) {
        if (!anchor || !anchor.hasAttribute || !anchor.hasAttribute('download')) return null;
        var href = String(anchor.href || '');
        return (href.indexOf('blob:') === 0 || href.indexOf('data:') === 0) ? href : null;
      }

      var nativeClick = HTMLAnchorElement.prototype.click;
      HTMLAnchorElement.prototype.click = function() {
        var href = inlineHref(this);
        if (!href) return nativeClick.apply(this, arguments);
        saveUrl(href, this.getAttribute('download'));
      };

      window.addEventListener('click', function(ev) {
        var target = ev.target;
        var anchor = (target && target.closest) ? target.closest('a') : null;
        var href = inlineHref(anchor);
        if (!href) return;
        ev.preventDefault();
        saveUrl(href, anchor.getAttribute('download'));
      }, true);
    } + ')(' +
      JSON.stringify(BLOB_REQ_EVENT) + ',' +
      JSON.stringify(BLOB_RES_EVENT) + ',' +
      CHUNK_BYTES + ',' +
      FORGET_REVOKED_MS +
    ');';
    try {
      root.appendChild(hookScript);
      hookScript.remove();
    } catch (err) {}
  }

  injectPageDownloadHook();

  window.addEventListener(BLOB_REQ_EVENT, async function(ev) {
    var msg;
    try {
      msg = JSON.parse(String(ev.detail || '{}'));
    } catch (err) {
      return;
    }
    var seq = msg.seq;
    var reply = { seq: seq, ok: false, id: '' };
    try {
      var res = await browser.runtime.sendNativeMessage(NATIVE_APP, msg);
      if (res && typeof res === 'object') {
        reply = Object.assign({ seq: seq }, res);
      }
    } catch (err) {
      reply = { seq: seq, ok: false, id: '' };
    }
    window.dispatchEvent(new CustomEvent(BLOB_RES_EVENT, { detail: JSON.stringify(reply) }));
  });

  // 3. 建立与当前 GeckoSession 的原生消息通道。
  var port = null;
  function connectPort() {
    try {
      port = browser.runtime.connectNative(NATIVE_APP);
      port.onMessage.addListener(handleHostMessage);
      port.onDisconnect.addListener(function() {
        port = null;
      });
    } catch (err) {
      port = null;
    }
  }
  connectPort();

  function postToHost(payload) {
    if (!port) return;
    try {
      port.postMessage(payload);
    } catch (err) {}
  }

  function isScroller(el) {
    if (!el || el.scrollHeight - el.clientHeight < 1) return false;
    if (el === document.scrollingElement) return true;
    var overflowY = getComputedStyle(el).overflowY;
    return overflowY === 'auto' || overflowY === 'scroll' || overflowY === 'overlay';
  }

  // 4. 视口、桌面版宽度与软键盘退避/焦点显露管理。
  var DEFAULT_PORTRAIT_VIEWPORT = 'width=device-width, initial-scale=1.0';
  var INTERACTIVE_WIDGET_DIRECTIVE = 'interactive-widget=resizes-content';
  var NON_TEXT_INPUT_TYPES = {
    button: true,
    checkbox: true,
    color: true,
    file: true,
    hidden: true,
    image: true,
    radio: true,
    range: true,
    reset: true,
    submit: true
  };
  var currentConfig = null;
  var originalViewportContent = null;
  var viewportListenersInstalled = false;
  var lastViewportHeight = 0;
  var revealRaf = 0;
  var revealTimer = 0;
  var isSyncingViewportMeta = false;

  // Gecko 132+ 默认使用 resizes-visual（弹键盘只缩 visualViewport，fixed bottom:0 仍按全屏底边定位），
  // 会让 MaiBot WebUI 手机底部弹窗/底栏反向向下掉进键盘后面；统一声明 resizes-content 让布局视口随键盘同缩。
  function withResizesContent(rawContent) {
    var source = String(rawContent || '').trim() || DEFAULT_PORTRAIT_VIEWPORT;
    var rawParts = source.split(',');
    var parts = [];
    for (var i = 0; i < rawParts.length; i++) {
      var token = rawParts[i].trim();
      if (!token) continue;
      var eqIndex = token.indexOf('=');
      var key = (eqIndex >= 0 ? token.slice(0, eqIndex) : token).trim().toLowerCase();
      if (key === 'interactive-widget') continue;
      parts.push(token);
    }
    parts.push(INTERACTIVE_WIDGET_DIRECTIVE);
    return parts.join(', ');
  }

  function captureOriginalViewport(metas) {
    if (originalViewportContent !== null) return;
    for (var i = 0; i < metas.length; i++) {
      if (!metas[i].hasAttribute('data-maidroid-created')) {
        originalViewportContent = metas[i].getAttribute('content') || '';
        return;
      }
    }
  }

  function syncViewportMeta(targetContent) {
    var parent = document.head || document.documentElement;
    if (!parent) return;

    isSyncingViewportMeta = true;
    try {
      var metas = Array.prototype.slice.call(document.querySelectorAll('meta[name="viewport"]'));
      captureOriginalViewport(metas);

      if (metas.length === 0) {
        var created = document.createElement('meta');
        created.name = 'viewport';
        created.setAttribute('data-maidroid-created', 'true');
        created.setAttribute('content', targetContent);
        parent.appendChild(created);
        return;
      }

      if (originalViewportContent !== null && metas[0].hasAttribute('data-maidroid-created')) {
        metas[0].removeAttribute('data-maidroid-created');
      }
      if (metas[0].getAttribute('content') !== targetContent) {
        metas[0].setAttribute('content', targetContent);
      }
      // 丢弃多余的 viewport meta，避免 HTML 后续解析到的第二条 viewport 覆盖桌面宽度或键盘视口策略
      for (var i = 1; i < metas.length; i++) {
        metas[i].remove();
      }
    } finally {
      isSyncingViewportMeta = false;
    }
  }

  function ensureViewportResizesContent() {
    var metas = Array.prototype.slice.call(document.querySelectorAll('meta[name="viewport"]'));
    if (metas.length === 0) return;
    captureOriginalViewport(metas);
    var baseContent = originalViewportContent !== null
      ? originalViewportContent
      : (metas[0].getAttribute('content') || DEFAULT_PORTRAIT_VIEWPORT);
    syncViewportMeta(withResizesContent(baseContent));
  }

  function readViewportHeight() {
    var vv = window.visualViewport;
    // 仅在未双指放大时采信 visualViewport.height，避免放大浏览时把容器高度错误压扁
    var visualHeight = (vv && (!vv.scale || vv.scale <= 1.01)) ? vv.height : 0;
    return Math.max(
      1,
      Math.round(
        visualHeight ||
        window.innerHeight ||
        (document.documentElement && document.documentElement.clientHeight) ||
        (document.body && document.body.clientHeight) ||
        1
      )
    );
  }

  function isEditableControl(el) {
    if (!el || el.nodeType !== 1) return false;
    if (el.disabled || el.readOnly) return false;
    var tag = el.tagName;
    if (tag === 'TEXTAREA') return true;
    if (tag === 'INPUT') {
      var type = String(el.type || 'text').toLowerCase();
      return !NON_TEXT_INPUT_TYPES[type];
    }
    return Boolean(el.isContentEditable);
  }

  function activeEditableControl() {
    var el = document.activeElement;
    while (el && el.shadowRoot && el.shadowRoot.activeElement) {
      el = el.shadowRoot.activeElement;
    }
    return isEditableControl(el) ? el : null;
  }

  function isRectClippedInBand(rect, minTop, maxBottom) {
    var bandHeight = maxBottom - minTop;
    if (bandHeight <= 0) return false;
    // 超出容器自身高度的大文本框只检查是否完全落出可见区，防止在上下边界来回跳动
    if (rect.height >= bandHeight) {
      return rect.bottom <= minTop || rect.top >= maxBottom;
    }
    return rect.top < minTop || rect.bottom > maxBottom;
  }

  function isControlClipped(el, viewportHeight) {
    var rect = el && el.getBoundingClientRect ? el.getBoundingClientRect() : null;
    if (!rect || rect.width <= 0 || rect.height <= 0) return false;
    if (isRectClippedInBand(rect, 4, viewportHeight - 4)) return true;

    // 顺着祖先滚动容器（兼容 Radix ScrollArea 与弹窗正文）检查是否被容器底栏/顶栏裁切
    var node = el.parentElement || (el.getRootNode && el.getRootNode().host) || null;
    while (node && node !== document.body && node !== document.documentElement) {
      if (node.nodeType === 1 && isScroller(node)) {
        var box = node.getBoundingClientRect();
        if (box && box.width > 0 && box.height > 0) {
          var margin = box.height >= rect.height + 16
            ? 8
            : Math.max(0, Math.floor((box.height - rect.height) / 2));
          if (isRectClippedInBand(rect, box.top + margin, box.bottom - margin)) {
            return true;
          }
        }
      }
      node = node.parentElement || (node.getRootNode && node.getRootNode().host) || null;
    }
    return false;
  }

  function revealFocusedControl(expectedEl) {
    var el = activeEditableControl();
    if (!el || (expectedEl && el !== expectedEl)) return;
    if (!isControlClipped(el, readViewportHeight())) return;
    try {
      el.scrollIntoView({ block: 'center', inline: 'nearest', behavior: 'instant' });
    } catch (err) {}
  }

  function scheduleEnsureFocusedControlVisible(expectedEl) {
    if (revealRaf && typeof window.cancelAnimationFrame === 'function') {
      window.cancelAnimationFrame(revealRaf);
    }
    revealRaf = 0;
    clearTimeout(revealTimer);
    revealTimer = 0;
    if (!expectedEl) return;

    // 首帧视口缩完先居中显露一次，140ms 后再复核（覆盖弹窗展开/输入法动画尾帧），已可见则零改动
    if (typeof window.requestAnimationFrame === 'function') {
      revealRaf = window.requestAnimationFrame(function() {
        revealRaf = 0;
        revealFocusedControl(expectedEl);
      });
    } else {
      revealFocusedControl(expectedEl);
    }
    revealTimer = setTimeout(function() {
      revealTimer = 0;
      revealFocusedControl(expectedEl);
    }, 140);
  }

  function applyPortraitViewportPatch(enabled) {
    var styleEl = document.getElementById(VIEWPORT_STYLE_ID);
    if (!enabled) {
      if (styleEl) styleEl.remove();
      if (document.documentElement) {
        document.documentElement.style.removeProperty('--maidroid-webview-height');
      }
      return;
    }

    var height = readViewportHeight();
    if (document.documentElement) {
      document.documentElement.style.setProperty('--maidroid-webview-height', height + 'px');
    }

    var parent = document.head || document.documentElement;
    if (!parent) return;

    var cssText = [
      'html,body,#root{width:100%!important;height:var(--maidroid-webview-height)!important;min-height:var(--maidroid-webview-height)!important;overflow:auto!important;overscroll-behavior:none!important}',
      '.min-h-screen{min-height:var(--maidroid-webview-height)!important}',
      '.h-screen{height:var(--maidroid-webview-height)!important}',
      '[class~="min-h-screen"][class~="items-center"][class~="justify-center"][class~="overflow-y-auto"],[class~="h-screen"][class~="items-center"][class~="justify-center"][class~="overflow-y-auto"]{justify-content:flex-start!important}',
      '[role="dialog"],[data-radix-dialog-content]{max-height:calc(var(--maidroid-webview-height) - 1rem)!important;overflow-y:auto!important}'
    ].join('\n');

    if (!styleEl) {
      styleEl = document.createElement('style');
      styleEl.id = VIEWPORT_STYLE_ID;
      parent.appendChild(styleEl);
    }
    if (styleEl.textContent !== cssText) {
      styleEl.textContent = cssText;
    }
  }

  function handleViewportResize() {
    var nextHeight = readViewportHeight();
    var shrank = false;
    // 对齐增量退避动画与一次性调整：累计收缩超 40px 时触发焦点输入框显露并重置基准高度
    if (lastViewportHeight <= 0 || nextHeight > lastViewportHeight) {
      lastViewportHeight = nextHeight;
    } else if (nextHeight < lastViewportHeight - 40) {
      shrank = true;
      lastViewportHeight = nextHeight;
    }

    if (currentConfig && currentConfig.desktopWidthCss == null && currentConfig.enableViewportPatch) {
      applyPortraitViewportPatch(true);
    }
    if (shrank) {
      scheduleEnsureFocusedControlVisible(activeEditableControl());
    }
  }

  function installViewportListeners() {
    if (viewportListenersInstalled) return;
    viewportListenersInstalled = true;
    lastViewportHeight = readViewportHeight();

    window.addEventListener('resize', handleViewportResize, { passive: true });
    window.addEventListener('orientationchange', function() {
      setTimeout(handleViewportResize, 50);
    }, { passive: true });
    if (window.visualViewport) {
      window.visualViewport.addEventListener('resize', handleViewportResize, { passive: true });
    }
    // 键盘已弹出时切换下一个输入框（点按或 Tab），若新焦点被容器遮挡则自动滚入可见区
    document.addEventListener('focusin', function(ev) {
      var el = activeEditableControl() || (isEditableControl(ev.target) ? ev.target : null);
      if (el) {
        scheduleEnsureFocusedControlVisible(el);
      }
    }, true);
  }

  installViewportListeners();

  function applyViewportConfig(config) {
    currentConfig = config;
    var parent = document.head || document.documentElement;
    if (!parent) return;

    var metas = Array.prototype.slice.call(document.querySelectorAll('meta[name="viewport"]'));
    captureOriginalViewport(metas);

    if (config.desktopWidthCss != null) {
      syncViewportMeta(withResizesContent('width=' + config.desktopWidthCss));
      applyPortraitViewportPatch(false);
    } else {
      var portraitBase = originalViewportContent !== null ? originalViewportContent : DEFAULT_PORTRAIT_VIEWPORT;
      syncViewportMeta(withResizesContent(portraitBase));
      applyPortraitViewportPatch(Boolean(config.enableViewportPatch));
    }
  }

  // 5. 网页底色采样上报（1x1 Canvas 转 sRGB，供横屏侧栏毛玻璃透出正确底色）。
  var bgCanvas = document.createElement('canvas');
  bgCanvas.width = 1;
  bgCanvas.height = 1;
  var bgContext = bgCanvas.getContext('2d', { willReadFrequently: true });
  var lastRawBgPair = '';
  var lastBgKey = '';
  var bgTimer = 0;
  var headObserverTimer = 0;

  function colorToBytes(color) {
    if (!bgContext) return [0, 0, 0, 0];
    bgContext.clearRect(0, 0, 1, 1);
    bgContext.fillStyle = 'rgba(0, 0, 0, 0)';
    bgContext.fillStyle = color;
    bgContext.fillRect(0, 0, 1, 1);
    var d = bgContext.getImageData(0, 0, 1, 1).data;
    return [d[0], d[1], d[2], d[3]];
  }

  function reportPageBackground(force) {
    if (!document.documentElement) return;
    var bodyBg = document.body ? (getComputedStyle(document.body).backgroundColor || '') : '';
    var rootBg = getComputedStyle(document.documentElement).backgroundColor || '';
    var rawPair = bodyBg + '|' + rootBg;
    if (!force && rawPair === lastRawBgPair) return;
    lastRawBgPair = rawPair;

    var bytes = bodyBg ? colorToBytes(bodyBg) : [0, 0, 0, 0];
    if (bytes[3] === 0 && rootBg) {
      bytes = colorToBytes(rootBg);
    }
    var key = bytes.join(',');
    if (!force && key === lastBgKey) return;
    lastBgKey = key;
    postToHost({
      type: 'pageBackground',
      r: bytes[0],
      g: bytes[1],
      b: bytes[2],
      a: bytes[3]
    });
  }

  function scheduleBackgroundReport() {
    clearTimeout(bgTimer);
    bgTimer = setTimeout(function() { reportPageBackground(false); }, 180);
  }

  function scheduleHeadSync() {
    clearTimeout(headObserverTimer);
    headObserverTimer = setTimeout(function() {
      headObserverTimer = 0;
      if (currentConfig) {
        applyViewportConfig(currentConfig);
      } else {
        ensureViewportResizesContent();
      }
      scheduleBackgroundReport();
    }, 120);
  }

  var observedBody = null;
  var observedHead = null;
  function attachBackgroundObservers() {
    if (document.body && observedBody !== document.body) {
      observedBody = document.body;
      new MutationObserver(scheduleBackgroundReport).observe(document.body, { attributes: true });
      reportPageBackground(false);
    }
    if (document.head && observedHead !== document.head) {
      observedHead = document.head;
      new MutationObserver(function() {
        if (isSyncingViewportMeta) return;
        scheduleHeadSync();
      }).observe(document.head, {
        childList: true,
        subtree: true,
        characterData: true
      });
      if (currentConfig) {
        applyViewportConfig(currentConfig);
      } else {
        ensureViewportResizesContent();
      }
    }
  }

  if (document.documentElement) {
    new MutationObserver(function() {
      attachBackgroundObservers();
      scheduleBackgroundReport();
    }).observe(document.documentElement, { attributes: true, childList: true });
  }
  window.addEventListener('DOMContentLoaded', function() {
    attachBackgroundObservers();
    if (currentConfig) {
      applyViewportConfig(currentConfig);
    } else {
      ensureViewportResizesContent();
    }
    reportPageBackground(false);
  });
  window.addEventListener('load', function() {
    if (currentConfig) {
      applyViewportConfig(currentConfig);
    } else {
      ensureViewportResizesContent();
    }
    reportPageBackground(false);
  });
  var darkQuery = window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)');
  if (darkQuery) {
    if (darkQuery.addEventListener) darkQuery.addEventListener('change', scheduleBackgroundReport);
    else if (darkQuery.addListener) darkQuery.addListener(scheduleBackgroundReport);
  }
  attachBackgroundObservers();

  // 6. 两指外层滚动状态机。
  var scrollState = { ratio: 1, chain: [], positions: new Map() };

  function positionEntryOf(el) {
    var entry = scrollState.positions.get(el);
    if (!entry || Math.abs(el.scrollTop - entry.applied) > 1) {
      entry = { pos: el.scrollTop, applied: el.scrollTop };
      scrollState.positions.set(el, entry);
    }
    return entry;
  }

  function handleTwoFingerBegin(x, y, viewWidth) {
    if (!viewWidth || viewWidth <= 0) return;
    var viewport = window.visualViewport;
    scrollState.ratio = ((viewport && viewport.width) || window.innerWidth) / viewWidth;
    var node = document.elementFromPoint(
      x * scrollState.ratio + (viewport ? viewport.offsetLeft : 0),
      y * scrollState.ratio + (viewport ? viewport.offsetTop : 0)
    );
    var chain = [];
    while (node) {
      if (node.nodeType === 1 && isScroller(node)) chain.unshift(node);
      node = node.parentElement || (node.getRootNode && node.getRootNode().host) || null;
    }
    var root = document.scrollingElement;
    if (root && chain.indexOf(root) < 0 && isScroller(root)) chain.unshift(root);
    scrollState.chain = chain;
    scrollState.positions = new Map();
  }

  function handleTwoFingerMove(dy) {
    var delta = -dy * scrollState.ratio;
    if (!delta) return true;
    var remaining = delta;
    var canContinue = false;
    for (var i = 0; i < scrollState.chain.length; i++) {
      var el = scrollState.chain[i];
      if (!el.isConnected) continue;
      var max = el.scrollHeight - el.clientHeight;
      var entry = positionEntryOf(el);
      if (remaining) {
        var next = Math.max(0, Math.min(max, entry.pos + remaining));
        if (next !== entry.pos) {
          remaining -= next - entry.pos;
          if (Math.abs(remaining) < 0.01) remaining = 0;
          entry.pos = next;
          el.scrollTo({ top: next, behavior: 'instant' });
          entry.applied = el.scrollTop;
        }
      }
      if (delta > 0 ? entry.pos < max - 0.5 : entry.pos > 0.5) {
        canContinue = true;
      }
    }
    return canContinue;
  }

  function handleHostMessage(msg) {
    if (!msg || typeof msg !== 'object') return;
    switch (msg.type) {
      case 'configure':
        applyViewportConfig(msg);
        reportPageBackground(true);
        break;
      case 'twoFingerBegin':
        handleTwoFingerBegin(msg.x, msg.y, msg.viewWidth);
        break;
      case 'twoFingerMove': {
        var canContinue = handleTwoFingerMove(msg.dy);
        if (msg.generation != null && msg.generation > 0) {
          postToHost({
            type: 'twoFingerResult',
            canContinue: canContinue,
            generation: msg.generation
          });
        }
        break;
      }
    }
  }
})();
