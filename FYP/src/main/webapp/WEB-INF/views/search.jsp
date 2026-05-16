<%@ page contentType="text/html;charset=UTF-8" language="java" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%@ taglib prefix="fmt" uri="jakarta.tags.fmt" %>
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>
        <c:choose>
            <c:when test="${not empty query}">Search: <c:out value="${query}"/> — AuctionHub</c:when>
            <c:otherwise>Search — AuctionHub</c:otherwise>
        </c:choose>
    </title>
    <link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/bootstrap@5.3.3/dist/css/bootstrap.min.css">
    <link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/bootstrap-icons@1.11.3/font/bootstrap-icons.min.css">
    <link rel="stylesheet" href="${pageContext.request.contextPath}/css/home.css">
</head>
<body>

<%@ include file="/WEB-INF/includes/home-navbar.jsp" %>

<main class="container py-4">

    <%-- ===== Input validation error (SCRUM-294) ===== --%>
    <c:if test="${not empty searchError}">
        <div class="alert alert-danger d-flex align-items-center gap-2" role="alert">
            <i class="bi bi-exclamation-triangle-fill"></i>
            <c:out value="${searchError}"/>
        </div>
    </c:if>

    <%-- ===== Header row ===== --%>
    <c:if test="${empty searchError}">
        <div class="d-flex flex-wrap align-items-baseline justify-content-between gap-2 mb-3">
            <h1 class="h4 mb-0">
                Results for &ldquo;<c:out value="${query}"/>&rdquo;
            </h1>
            <span class="text-muted small">
                <c:choose>
                    <c:when test="${total == 1}">1 listing found</c:when>
                    <c:otherwise><c:out value="${total}"/> listings found</c:otherwise>
                </c:choose>
            </span>
        </div>

        <%-- ===== SCRUM-259: empty results UX ===== --%>
        <c:if test="${searchEmpty}">
            <div class="text-center py-5">
                <i class="bi bi-search display-3 text-muted mb-3 d-block"></i>
                <h2 class="h5">No results found for &ldquo;<c:out value="${query}"/>&rdquo;</h2>
                <p class="text-muted">Try different keywords, check spelling, or browse all categories.</p>
                <a href="${pageContext.request.contextPath}/" class="btn btn-primary mt-2">
                    <i class="bi bi-house me-1"></i>Back to Home
                </a>
            </div>
        </c:if>

        <%-- ===== Results grid ===== --%>
        <c:if test="${not searchEmpty}">
            <div class="row row-cols-1 row-cols-sm-2 row-cols-lg-3 row-cols-xl-4 g-4 mb-4">
                <c:forEach var="item" items="${results}">
                    <div class="col">
                        <div class="card h-100 shadow-sm">
                            <c:choose>
                                <c:when test="${not empty item.thumbnailUrl}">
                                    <img src="<c:out value="${item.thumbnailUrl}"/>"
                                         class="card-img-top object-fit-cover"
                                         style="height:180px"
                                         alt="<c:out value="${item.title}"/>">
                                </c:when>
                                <c:otherwise>
                                    <div class="card-img-top bg-light d-flex align-items-center justify-content-center"
                                         style="height:180px">
                                        <i class="bi bi-image text-muted fs-1"></i>
                                    </div>
                                </c:otherwise>
                            </c:choose>
                            <div class="card-body d-flex flex-column">
                                <span class="badge text-bg-secondary mb-1 align-self-start small">
                                    <c:out value="${item.category}"/>
                                </span>
                                <h5 class="card-title fs-6 fw-semibold mb-1">
                                    <c:out value="${item.title}"/>
                                </h5>
                                <p class="card-text text-muted small mb-auto">
                                    Seller: <c:out value="${item.sellerUsername}"/>
                                </p>
                            </div>
                            <div class="card-footer d-flex justify-content-between align-items-center">
                                <div>
                                    <div class="small text-muted">Current bid</div>
                                    <strong class="text-primary">
                                        <fmt:formatNumber value="${item.currentPrice}" type="currency" currencyCode="USD"/>
                                    </strong>
                                </div>
                                <a href="${pageContext.request.contextPath}/auction/${item.auctionId}"
                                   class="btn btn-outline-primary btn-sm">
                                    View
                                </a>
                            </div>
                        </div>
                    </div>
                </c:forEach>
            </div>

            <%-- ===== Pagination ===== --%>
            <c:if test="${totalPages > 1}">
                <nav aria-label="Search result pages">
                    <ul class="pagination justify-content-center flex-wrap">
                        <li class="page-item ${currentPage <= 1 ? 'disabled' : ''}">
                            <a class="page-link"
                               href="?q=<c:out value="${query}"/>&page=${currentPage - 1}&size=${pageSize}"
                               aria-label="Previous">
                                <i class="bi bi-chevron-left"></i>
                            </a>
                        </li>
                        <c:forEach begin="1" end="${totalPages}" var="p">
                            <li class="page-item ${p == currentPage ? 'active' : ''}">
                                <a class="page-link"
                                   href="?q=<c:out value="${query}"/>&page=${p}&size=${pageSize}">
                                    ${p}
                                </a>
                            </li>
                        </c:forEach>
                        <li class="page-item ${currentPage >= totalPages ? 'disabled' : ''}">
                            <a class="page-link"
                               href="?q=<c:out value="${query}"/>&page=${currentPage + 1}&size=${pageSize}"
                               aria-label="Next">
                                <i class="bi bi-chevron-right"></i>
                            </a>
                        </li>
                    </ul>
                </nav>
            </c:if>
        </c:if>
    </c:if>

</main>

<%@ include file="/WEB-INF/includes/home-footer.jsp" %>

<script src="https://cdn.jsdelivr.net/npm/bootstrap@5.3.3/dist/js/bootstrap.bundle.min.js"></script>
</body>
</html>
