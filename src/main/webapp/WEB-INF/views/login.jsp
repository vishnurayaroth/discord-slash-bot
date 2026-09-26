<%@ page contentType="text/html;charset=UTF-8" pageEncoding="UTF-8" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>Sign in</title>
  <link rel="stylesheet" href="<c:url value='/static/css/app.css'/>">
</head>
<body class="centered">
  <main class="card narrow">
    <h1>Sign in</h1>
    <c:if test="${not empty error}">
      <p class="error" role="alert"><c:out value="${error}"/></p>
    </c:if>
    <form method="post" action="<c:url value='/login'/>">
      <label>Username
        <input type="text" name="username" autocomplete="username" required autofocus>
      </label>
      <label>Password
        <input type="password" name="password" autocomplete="current-password" required>
      </label>
      <button type="submit">Sign in</button>
    </form>
  </main>
</body>
</html>
