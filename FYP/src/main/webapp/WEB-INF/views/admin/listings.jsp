<%@ page contentType="text/html;charset=UTF-8" language="java" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%@ taglib prefix="fmt" uri="jakarta.tags.fmt" %>
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Listing Moderation — Admin</title>
    <link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/bootstrap@5.3.3/dist/css/bootstrap.min.css">
    <link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/bootstrap-icons@1.11.3/font/bootstrap-icons.min.css">
    <link rel="stylesheet" href="${pageContext.request.contextPath}/css/admin.css">
</head>
<body class="admin-body">
<div class="admin-layout">
    <%@ include file="/WEB-INF/includes/admin-sidebar.jspf" %>
    <main class="admin-main flex-grow-1 p-3 p-lg-4">
        <header class="mb-4">
            <h1 class="admin-page-title mb-1">Listing Moderation</h1>
            <p class="admin-subtitle mb-0">Review and moderate auction listings.</p>
        </header>

        <c:if test="${not empty adminFlash}">
            <div class="alert alert-success py-2 small"><c:out value="${adminFlash}"/></div>
        </c:if>
        <c:if test="${not empty adminFlashError}">
            <div class="alert alert-danger py-2 small"><c:out value="${adminFlashError}"/></div>
        </c:if>

        <div class="admin-table-card">
            <div class="table-responsive">
                <table class="table table-hover align-middle mb-0">
                    <thead class="table-light">
                    <tr>
                        <th scope="col">Listing</th>
                        <th scope="col" class="d-none d-md-table-cell">Seller</th>
                        <th scope="col">Category</th>
                        <th scope="col" class="text-end">Current Bid</th>
                        <th scope="col">Reports</th>
                        <th scope="col">Status</th>
                        <th scope="col" class="text-end">Actions</th>
                    </tr>
                    </thead>
                    <tbody>
                    <c:forEach var="l" items="${listings}">
                        <tr>
                            <td>
                                <div class="fw-semibold"><c:out value="${l.title}"/></div>
                                <div class="small text-muted">Listed <c:out value="${l.listedDate}"/></div>
                            </td>
                            <td class="d-none d-md-table-cell"><c:out value="${l.sellerUsername}"/></td>
                            <td><c:out value="${l.category}"/></td>
                            <td class="text-end"><fmt:formatNumber value="${l.currentBid}" type="currency" currencyCode="USD"/></td>
                            <td>
                                <c:set var="rc" value="${l.reportCount}"/>
                                <c:choose>
                                    <c:when test="${rc == 0}"><span class="badge text-bg-secondary">${rc}</span></c:when>
                                    <c:when test="${rc lt 5}"><span class="badge text-bg-warning text-dark">${rc}</span></c:when>
                                    <c:otherwise><span class="badge text-bg-danger">${rc}</span></c:otherwise>
                                </c:choose>
                            </td>
                            <td>
                                <c:choose>
                                    <c:when test="${l.moderationState == 'active'}"><span class="badge text-bg-success">active</span></c:when>
                                    <c:when test="${l.moderationState == 'flagged'}"><span class="badge text-bg-warning text-dark">flagged</span></c:when>
                                    <c:otherwise><span class="badge text-bg-danger">removed</span></c:otherwise>
                                </c:choose>
                            </td>
                            <td class="text-end">
                                <div class="d-flex flex-wrap gap-1 justify-content-end">
                                    <c:if test="${l.moderationState == 'active'}">
                                        <form class="d-inline" method="post" action="${pageContext.request.contextPath}/admin/listings"
                                              data-admin-listing-action>
                                            <input type="hidden" name="action" value="FLAG"/>
                                            <input type="hidden" name="auctionId" value="${l.auctionId}"/>
                                            <button type="submit" class="btn btn-warning btn-sm" data-confirm="Flag this listing?">Flag</button>
                                        </form>
                                    </c:if>
                                    <c:if test="${l.moderationState == 'active' || l.moderationState == 'flagged'}">
                                        <form class="d-inline" method="post" action="${pageContext.request.contextPath}/admin/listings"
                                              data-admin-listing-action>
                                            <input type="hidden" name="action" value="REMOVE"/>
                                            <input type="hidden" name="auctionId" value="${l.auctionId}"/>
                                            <button type="submit" class="btn btn-danger btn-sm" data-confirm="Remove this listing from public view?">Remove</button>
                                        </form>
                                    </c:if>
                                    <c:if test="${l.moderationState == 'removed'}">
                                        <form class="d-inline" method="post" action="${pageContext.request.contextPath}/admin/listings"
                                              data-admin-listing-action>
                                            <input type="hidden" name="action" value="RESTORE"/>
                                            <input type="hidden" name="auctionId" value="${l.auctionId}"/>
                                            <button type="submit" class="btn btn-success btn-sm" data-confirm="Restore this listing to active?">Restore</button>
                                        </form>
                                    </c:if>
                                </div>
                            </td>
                        </tr>
                    </c:forEach>
                    </tbody>
                </table>
            </div>
        </div>
    </main>
</div>
<script src="https://cdn.jsdelivr.net/npm/bootstrap@5.3.3/dist/js/bootstrap.bundle.min.js"></script>
<script src="${pageContext.request.contextPath}/js/admin-panel.js"></script>
</body>
</html>
