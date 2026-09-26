<%@ page contentType="text/html;charset=UTF-8" pageEncoding="UTF-8" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>Commands</title>
  <link rel="stylesheet" href="<c:url value='/static/css/app.css'/>">
</head>
<body>
  <%@ include file="nav.jspf" %>
  <main>
    <h1>Commands</h1>
    <p class="muted">Changes apply to the very next command. No restart is needed.</p>

    <c:if test="${not empty notice}"><p class="notice" role="status"><c:out value="${notice}"/></p></c:if>
    <c:if test="${not empty error}"><p class="error" role="alert"><c:out value="${error}"/></p></c:if>

    <c:forEach var="cmd" items="${commands}">
      <section class="panel">
        <h2>/<c:out value="${cmd.command}"/></h2>
        <form method="post" action="<c:url value='/dashboard/config'/>">
          <input type="hidden" name="csrf" value="<c:out value='${csrf}'/>">
          <input type="hidden" name="command" value="<c:out value='${cmd.command}'/>">
          <label>Status
            <select name="enabled">
              <option value="true" ${cmd.enabled ? 'selected' : ''}>Enabled</option>
              <option value="false" ${cmd.enabled ? '' : 'selected'}>Disabled</option>
            </select>
          </label>
          <label>Reply text
            <textarea name="replyText" required><c:out value="${cmd.replyText}"/></textarea>
          </label>
          <div class="row">
            <button type="submit">Save</button>
            <span class="muted">Last saved <c:out value="${cmd.updatedAt}"/></span>
          </div>
        </form>
      </section>
    </c:forEach>

    <section class="panel">
      <h2>Second channel</h2>
      <p>Notifications are sent to <code><c:out value="${mirror}"/></code>.</p>
      <p class="muted">This address is a secret set with the deployment's environment variables. It cannot be changed or shown in full here.</p>
    </section>
  </main>
</body>
</html>
