(function () {
  'use strict';

  // Live log: polls /api/log every 3 s and redraws the newest entries. All untrusted text is
  // written with textContent, never as markup (FR-021). Polling pauses when the tab is hidden and
  // after 10 minutes without input, so an idle tab cannot keep the free database awake
  // (research.md R4, R9).
  var POLL_MS = 3000;
  var IDLE_LIMIT_MS = 10 * 60 * 1000;

  var table = document.getElementById('log');
  var body = document.getElementById('log-body');
  var status = document.getElementById('log-status');
  if (!table || !body || !status) {
    return;
  }
  var api = table.getAttribute('data-api');
  var loginUrl = table.getAttribute('data-login');

  var lastInput = Date.now();
  var timer = null;
  var paused = false;

  function cell(value, className) {
    var td = document.createElement('td');
    td.textContent = value === null || value === undefined ? '' : String(value);
    if (className) {
      td.className = className;
    }
    return td;
  }

  function formatTime(iso) {
    var d = new Date(iso);
    return isNaN(d.getTime()) ? iso : d.toLocaleString();
  }

  function actionsCell(actions) {
    var td = document.createElement('td');
    (actions || []).forEach(function (a) {
      var line = document.createElement('div');
      line.className = 'action ' + a.status;
      var attempts = a.attempts ? ' (' + a.attempts + ')' : '';
      var error = a.lastError ? ' - ' + a.lastError : '';
      line.textContent = a.kind + ': ' + a.status + attempts + error;
      td.appendChild(line);
    });
    return td;
  }

  function render(entries) {
    body.textContent = '';
    entries.forEach(function (e) {
      var tr = document.createElement('tr');
      if (e.priority) {
        tr.className = 'priority';
      }
      tr.appendChild(cell(formatTime(e.receivedAt)));
      tr.appendChild(cell(e.member));
      tr.appendChild(cell('/' + e.command));
      tr.appendChild(cell(e.text, 'wrap'));
      tr.appendChild(cell(e.priority ? 'HIGH' : ''));
      tr.appendChild(cell(e.outcome + ' / ' + e.overall));
      tr.appendChild(actionsCell(e.actions));
      body.appendChild(tr);
    });
    if (entries.length === 0) {
      var tr = document.createElement('tr');
      var td = cell('No commands yet.', 'muted');
      td.colSpan = 7;
      tr.appendChild(td);
      body.appendChild(tr);
    }
  }

  function poll() {
    fetch(api, { credentials: 'same-origin', headers: { Accept: 'application/json' } })
      .then(function (response) {
        if (response.status === 401) {
          window.location = loginUrl;
          return null;
        }
        return response.json();
      })
      .then(function (data) {
        if (data && data.entries) {
          render(data.entries);
          status.textContent = 'Live. Updated ' + new Date().toLocaleTimeString();
        } else if (data && data.error) {
          status.textContent = 'The service could not read the log right now. Retrying...';
        }
      })
      .catch(function () {
        status.textContent = 'Connection problem. Retrying...';
      });
  }

  function shouldPause() {
    return document.hidden || Date.now() - lastInput > IDLE_LIMIT_MS;
  }

  function tick() {
    timer = null;
    if (shouldPause()) {
      paused = true;
      status.textContent = 'Live updates paused. Move the mouse or press a key to resume.';
      return;
    }
    paused = false;
    poll();
    timer = setTimeout(tick, POLL_MS);
  }

  function activity() {
    lastInput = Date.now();
    if (paused && timer === null && !document.hidden) {
      paused = false;
      tick();
    }
  }

  ['mousemove', 'keydown', 'click', 'touchstart', 'scroll'].forEach(function (name) {
    window.addEventListener(name, activity, { passive: true });
  });
  document.addEventListener('visibilitychange', function () {
    if (!document.hidden) {
      lastInput = Date.now();
      if (timer === null) {
        tick();
      }
    }
  });

  tick();
})();
