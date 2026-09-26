<%@ page contentType="text/html;charset=UTF-8" pageEncoding="UTF-8" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>Command log</title>
  <link rel="stylesheet" href="<c:url value='/static/css/app.css'/>">
</head>
<body>
  <%@ include file="nav.jspf" %>
  <main>
    <h1>Command log</h1>
    <p id="log-status" class="muted" aria-live="polite">Loading...</p>
    <noscript><p class="error">JavaScript is needed to show the live log.</p></noscript>
    <div class="table-wrap">
      <table id="log" data-api="<c:url value='/api/log'/>" data-login="<c:url value='/login'/>">
        <thead>
          <tr><th>Time</th><th>Member</th><th>Command</th><th>Text</th><th>Priority</th><th>Outcome</th><th>Actions</th></tr>
        </thead>
        <tbody id="log-body"></tbody>
      </table>
    </div>
  </main>
  <script src="<c:url value='/static/js/live-log.js'/>" defer></script>
</body>
</html>
