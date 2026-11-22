(function() {
  if (window.__maidroidBridgeLoaded) return;
  window.__maidroidBridgeLoaded = true;

  var VIEWPORT_STYLE_ID = 'maidroid-webview-viewport-fix';
  var NATIVE_APP = 'maidroid';
  var BLOB_REQ_EVENT = '__maidroid_blob_req';
  var BLOB_RES_EVENT = '__maidroid_blob_res';
  var CHUNK_BYTES = 512 * 1024;
  var FORGET_REVOKED_MS = 60000;

  // 1. 首次加载补全深色与未来复古主题默认值，不覆盖用户已主动修改过的设置。
  try {
    if (localStorage.getItem('maibot-theme-mode') === null) {
      localStorage.setItem('maibot-theme-mode', 'dark');
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

  // 4. 视口与桌面版宽度管理。
  var currentConfig = null;
  var originalViewportContent = null;
  var viewportListenersInstalled = false;

  function readViewportHeight() {
    var visualHeight = window.visualViewport && window.visualViewport.height;
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

    if (!viewportListenersInstalled) {
      viewportListenersInstalled = true;
      var refreshPortraitHeight = function() {
        if (currentConfig && currentConfig.desktopWidthCss == null && currentConfig.enableViewportPatch) {
          applyPortraitViewportPatch(true);
        }
      };
      window.addEventListener('resize', refreshPortraitHeight, { passive: true });
      window.addEventListener('orientationchange', function() {
        setTimeout(refreshPortraitHeight, 50);
      }, { passive: true });
      if (window.visualViewport) {
        window.visualViewport.addEventListener('resize', refreshPortraitHeight, { passive: true });
      }
    }
  }

  function applyViewportConfig(config) {
    currentConfig = config;
    var parent = document.head || document.documentElement;
    if (!parent) return;

    var metas = Array.prototype.slice.call(document.querySelectorAll('meta[name="viewport"]'));
    if (config.desktopWidthCss != null) {
      var targetContent = 'width=' + config.desktopWidthCss;
      if (metas.length > 0 && originalViewportContent === null && !metas[0].hasAttribute('data-maidroid-created')) {
        originalViewportContent = metas[0].getAttribute('content');
      }
      if (metas.length === 0) {
        var created = document.createElement('meta');
        created.name = 'viewport';
        created.setAttribute('data-maidroid-created', 'true');
        created.setAttribute('content', targetContent);
        parent.appendChild(created);
      } else {
        for (var i = 0; i < metas.length; i++) {
          if (metas[i].getAttribute('content') !== targetContent) {
            metas[i].setAttribute('content', targetContent);
          }
        }
        // 丢弃多余的 viewport meta，避免 HTML 后续解析到的第二条 viewport 覆盖桌面宽度
        for (var j = 1; j < metas.length; j++) {
          metas[j].remove();
        }
      }
      applyPortraitViewportPatch(false);
    } else {
      if (metas.length > 0 && originalViewportContent !== null) {
        metas[0].setAttribute('content', originalViewportContent);
        originalViewportContent = null;
      } else if (metas.length > 0 && metas[0].hasAttribute('data-maidroid-created')) {
        metas[0].removeAttribute('data-maidroid-created');
        metas[0].setAttribute('content', 'width=device-width, initial-scale=1.0');
      }
      applyPortraitViewportPatch(Boolean(config.enableViewportPatch));
    }
  }

  // 5. 网页底色采样上报（1x1 Canvas 转 sRGB，供横屏侧栏毛玻璃透出正确底色）。
  var bgCanvas = document.createElement('canvas');
  bgCanvas.width = 1;
  bgCanvas.height = 1;
  var bgContext = bgCanvas.getContext('2d', { willReadFrequently: true });
  var lastBgKey = '';
  var bgTimer = 0;

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
    var bytes = document.body
      ? colorToBytes(getComputedStyle(document.body).backgroundColor)
      : [0, 0, 0, 0];
    if (bytes[3] === 0) {
      bytes = colorToBytes(getComputedStyle(document.documentElement).backgroundColor);
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
    reportPageBackground(false);
    clearTimeout(bgTimer);
    bgTimer = setTimeout(function() { reportPageBackground(false); }, 400);
  }

  var observedBody = null;
  var observedHead = null;
  function attachBackgroundObservers() {
    if (document.body && observedBody !== document.body) {
      observedBody = document.body;
      new MutationObserver(scheduleBackgroundReport).observe(document.body, { attributes: true });
      scheduleBackgroundReport();
    }
    if (document.head && observedHead !== document.head) {
      observedHead = document.head;
      new MutationObserver(function() {
        if (currentConfig && currentConfig.desktopWidthCss != null) {
          applyViewportConfig(currentConfig);
        }
        scheduleBackgroundReport();
      }).observe(document.head, {
        childList: true,
        subtree: true,
        characterData: true
      });
      if (currentConfig) applyViewportConfig(currentConfig);
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
    if (currentConfig) applyViewportConfig(currentConfig);
    scheduleBackgroundReport();
  });
  window.addEventListener('load', function() {
    if (currentConfig) applyViewportConfig(currentConfig);
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

  function isScroller(el) {
    if (!el || el.scrollHeight - el.clientHeight < 1) return false;
    if (el === document.scrollingElement) return true;
    var overflowY = getComputedStyle(el).overflowY;
    return overflowY === 'auto' || overflowY === 'scroll' || overflowY === 'overlay';
  }

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
