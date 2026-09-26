<%@ page contentType="text/html;charset=UTF-8" pageEncoding="UTF-8" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>Server connection</title>
  <link rel="stylesheet" href="<c:url value='/static/css/app.css'/>">
</head>
<body>
  <%@ include file="nav.jspf" %>
  <main>
    <h1>Server connection</h1>

    <c:if test="${not empty notice}"><p class="notice" role="status"><c:out value="${notice}"/></p></c:if>
    <c:if test="${not empty error}"><p class="error" role="alert"><c:out value="${error}"/></p></c:if>

    <section class="panel">
      <h2>Current connection</h2>
      <c:choose>
        <c:when test="${not empty connection}">
          <p>Server <strong><c:out value="${connection.guildName}"/></strong>, posting to
             <strong>#<c:out value="${connection.channelName}"/></strong>.</p>
        </c:when>
        <c:otherwise><p class="muted">No server is connected yet.</p></c:otherwise>
      </c:choose>
    </section>

    <section class="panel">
      <h2>1. Add the bot to your server</h2>
      <p>Open the invite link, choose your server and approve. The bot only needs to view and post in channels.</p>
      <p><a href="<c:out value='${invite}'/>" target="_blank" rel="noopener noreferrer">Add the bot to a server</a></p>
    </section>

    <section class="panel">
      <h2>2. Choose the server</h2>
      <p><a href="<c:url value='/dashboard/connect?refresh=1'/>">Refresh the list of servers</a></p>
      <c:if test="${not empty guilds}">
        <ul>
          <c:forEach var="g" items="${guilds}">
            <li>
              <a href="<c:url value='/dashboard/connect'><c:param name='guild' value='${g.id}'/></c:url>"><c:out value="${g.name}"/></a>
              <c:if test="${g.id == guildId}"> (selected)</c:if>
            </li>
          </c:forEach>
        </ul>
      </c:if>
      <c:if test="${empty guilds and param.refresh == '1'}"><p class="muted">The bot is not in any server yet.</p></c:if>
    </section>

    <c:if test="${not empty guildId}">
      <section class="panel">
        <h2>3. Choose the channel and connect</h2>
        <c:choose>
          <c:when test="${not empty channels}">
            <form method="post" action="<c:url value='/dashboard/connect'/>">
              <input type="hidden" name="csrf" value="<c:out value='${csrf}'/>">
              <input type="hidden" name="guildId" value="<c:out value='${guildId}'/>">
              <label>Channel for report posts
                <select name="channelId" required>
                  <c:forEach var="ch" items="${channels}">
                    <option value="<c:out value='${ch.id}'/>">#<c:out value="${ch.name}"/></option>
                  </c:forEach>
                </select>
              </label>
              <button type="submit">Connect and register commands</button>
            </form>
            <p class="muted">The bot sends a short test message to the channel first. Nothing is saved if it cannot post there.</p>
          </c:when>
          <c:otherwise><p class="muted">No text channels were found for that server.</p></c:otherwise>
        </c:choose>
      </section>
    </c:if>
  </main>
</body>
</html>
