// Injected by the Android app into pages from the configured server only.
// Talks to the app through window.HyperionAndroid.postMessage, which the app
// also restricts to that origin.
(function () {
  'use strict';
  if (window.__hyperionAndroid) return;
  window.__hyperionAndroid = true;

  var bridge = window.HyperionAndroid;
  if (!bridge || typeof bridge.postMessage !== 'function') return;

  function send(message) {
    try {
      bridge.postMessage(JSON.stringify(message));
    } catch (_e) {
      // The app went away mid-call. Nothing useful to do.
    }
  }

  // ---- Downloads made in the page (exports, vCards, backups) ----
  // The webapp builds these as blob: URLs, clicks a hidden <a download>, then
  // revokes the URL straight away. WebView cannot save blob: URLs itself, so
  // keep a reference to each blob long enough to hand its bytes to the app.
  var blobs = new Map();
  var createObjectURL = URL.createObjectURL;
  var revokeObjectURL = URL.revokeObjectURL;

  URL.createObjectURL = function (object) {
    var url = createObjectURL.call(URL, object);
    if (object instanceof Blob) blobs.set(url, object);
    return url;
  };

  URL.revokeObjectURL = function (url) {
    setTimeout(function () {
      blobs.delete(url);
      revokeObjectURL.call(URL, url);
    }, 60000);
  };

  function isLocalUrl(href) {
    return href.indexOf('blob:') === 0 || href.indexOf('data:') === 0;
  }

  function save(href, filename) {
    var known = blobs.get(href);
    var pending = known ? Promise.resolve(known) : fetch(href).then(function (r) { return r.blob(); });
    pending
      .then(function (blob) {
        var reader = new FileReader();
        reader.onload = function () {
          var result = String(reader.result);
          send({
            type: 'download',
            name: filename || 'download',
            mime: blob.type || 'application/octet-stream',
            data: result.slice(result.indexOf(',') + 1),
          });
        };
        reader.onerror = function () { send({ type: 'download-failed' }); };
        reader.readAsDataURL(blob);
      })
      .catch(function () { send({ type: 'download-failed' }); });
  }

  // Programmatic link.click(), which is how every export in the webapp works.
  var anchorClick = HTMLAnchorElement.prototype.click;
  HTMLAnchorElement.prototype.click = function () {
    var href = this.href || '';
    if (this.hasAttribute('download') && isLocalUrl(href)) {
      save(href, this.getAttribute('download'));
      return;
    }
    return anchorClick.apply(this, arguments);
  };

  // A real tap on such a link.
  document.addEventListener('click', function (event) {
    var target = event.target;
    var link = target && target.closest ? target.closest('a[download]') : null;
    if (!link) return;
    var href = link.href || '';
    if (!isLocalUrl(href)) return;
    event.preventDefault();
    save(href, link.getAttribute('download'));
  }, true);

  // ---- Theme ----
  // Colour the system bars like the page. ThemeProvider keeps the theme-color
  // meta tag and the "dark" class on <html> in sync with the user's setting.
  var lastTheme = '';
  function reportTheme() {
    var meta = document.querySelector('meta[name="theme-color"]');
    var color = meta ? meta.getAttribute('content') || '' : '';
    var dark = document.documentElement.classList.contains('dark');
    var key = color + '|' + dark;
    if (key === lastTheme) return;
    lastTheme = key;
    send({ type: 'theme', color: color, dark: dark });
  }

  function watchTheme() {
    reportTheme();
    new MutationObserver(reportTheme).observe(document.documentElement, {
      attributes: true,
      attributeFilter: ['class'],
    });
    if (document.head) {
      new MutationObserver(reportTheme).observe(document.head, {
        subtree: true,
        childList: true,
        attributes: true,
        attributeFilter: ['content'],
      });
    }
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', watchTheme);
  } else {
    watchTheme();
  }
})();
