/* 为旧版 Android System WebView 补齐 MaiBot 桌宠页面依赖的基础 Web API 与布局标识类。 */
(function () {
  'use strict';

  if (typeof Object.hasOwn !== 'function') {
    Object.defineProperty(Object, 'hasOwn', {
      value: function (obj, prop) {
        if (obj === null || obj === undefined) {
          throw new TypeError('Cannot convert undefined or null to object');
        }
        return Object.prototype.hasOwnProperty.call(Object(obj), prop);
      },
      configurable: true,
      writable: true,
    });
  }

  if (typeof String.prototype.replaceAll !== 'function') {
    String.prototype.replaceAll = function (search, replacement) {
      var target = String(this);
      if (search instanceof RegExp) {
        if (!search.global) {
          throw new TypeError('String.prototype.replaceAll called with a non-global RegExp argument');
        }
        return target.replace(search, replacement);
      }
      var needle = String(search);
      if (needle === '') {
        return typeof replacement === 'function'
          ? target.replace(/(?:)/g, replacement)
          : target.split('').join(String(replacement));
      }
      if (typeof replacement === 'function') {
        var out = '';
        var start = 0;
        var idx = target.indexOf(needle, start);
        while (idx !== -1) {
          out += target.slice(start, idx) + String(replacement(needle, idx, target));
          start = idx + needle.length;
          idx = target.indexOf(needle, start);
        }
        return out + target.slice(start);
      }
      return target.split(needle).join(String(replacement));
    };
  }

  if (typeof Array.prototype.at !== 'function') {
    Object.defineProperty(Array.prototype, 'at', {
      value: function (n) {
        var len = this.length >>> 0;
        var k = Math.trunc(Number(n)) || 0;
        if (k < 0) k += len;
        if (k < 0 || k >= len) return undefined;
        return this[k];
      },
      configurable: true,
      writable: true,
    });
  }

  if (typeof window.crypto !== 'undefined' && typeof window.crypto.randomUUID !== 'function') {
    window.crypto.randomUUID = function () {
      var bytes = new Uint8Array(16);
      if (typeof window.crypto.getRandomValues === 'function') {
        window.crypto.getRandomValues(bytes);
      } else {
        for (var i = 0; i < 16; i++) bytes[i] = Math.floor(Math.random() * 256);
      }
      bytes[6] = (bytes[6] & 0x0f) | 0x40;
      bytes[8] = (bytes[8] & 0x3f) | 0x80;
      var hex = '';
      for (var j = 0; j < 16; j++) {
        var b = bytes[j].toString(16);
        hex += b.length === 1 ? '0' + b : b;
      }
      return (
        hex.slice(0, 8) + '-' +
        hex.slice(8, 12) + '-' +
        hex.slice(12, 16) + '-' +
        hex.slice(16, 20) + '-' +
        hex.slice(20)
      );
    };
  }

  // 不支持 CSS :has() 的旧内核通过 body.pet-records-page 标识探索记录页以命中移动端自适应样式。
  function syncRecordsBodyClass() {
    if (document.body && document.getElementById('titlebar')) {
      document.body.classList.add('pet-records-page');
    }
  }
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', syncRecordsBodyClass, { once: true });
  } else {
    syncRecordsBodyClass();
  }
})();
