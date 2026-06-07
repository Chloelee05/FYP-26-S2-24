<%@ page contentType="text/html;charset=UTF-8" language="java" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%@ taglib prefix="fmt" uri="jakarta.tags.fmt" %>
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Bid History — Auction #${auctionId} — AuctionHub</title>
    <link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/bootstrap@5.3.3/dist/css/bootstrap.min.css">
    <link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/bootstrap-icons@1.11.3/font/bootstrap-icons.min.css">
    <style>
        body { background-color: #f8f9fa; }
    </style>
</head>
<body>
<%@ include file="/WEB-INF/includes/home-navbar.jsp" %>

<div class="container py-4">
    <nav aria-label="breadcrumb" class="mb-3">
        <ol class="breadcrumb">
            <li class="breadcrumb-item">
                <a href="${pageContext.request.contextPath}/auction/${auctionId}">Auction #${auctionId}</a>
            </li>
            <li class="breadcrumb-item active" aria-current="page">Bid History</li>
        </ol>
    </nav>

    <h1 class="h4 fw-bold mb-4">
        <i class="bi bi-clock-history me-2"></i>Bid History
        <span class="text-muted fw-normal small">(Auction #${auctionId})</span>
    </h1>

    <c:choose>
        <c:when test="${bidHistoryEmpty}">
            <p class="text-muted">No bids have been placed on this auction yet.</p>
        </c:when>
        <c:otherwise>
            <div class="table-responsive mb-3">
                <table class="table table-hover align-middle bg-white rounded shadow-sm">
                    <thead class="table-light">
                        <tr>
                            <th>Bidder</th>
                            <th class="text-end">Amount</th>
                            <th class="text-end">Time</th>
                        </tr>
                    </thead>
                    <tbody>
                        <c:forEach var="b" items="${bidHistory}">
                            <tr class="${b.currentLeader ? 'table-success' : ''}">
                                <td>
                                    <c:out value="${b.maskedBidderName}"/>
                                    <c:if test="${b.currentLeader}">
                                        <span class="badge text-bg-success ms-1">Leading</span>
                                    </c:if>
                                </td>
                                <td class="text-end fw-semibold">
                                    $<fmt:formatNumber value="${b.bidAmount}" pattern="#,##0.00"/>
                                </td>
                                <td class="text-end text-muted text-nowrap">
                                    <fmt:formatDate value="${b.bidTimeDate}"
                                                    pattern="dd MMM yyyy, HH:mm"/>
                                </td>
                            </tr>
                        </c:forEach>
                    </tbody>
                </table>
            </div>
        </c:otherwise>
    </c:choose>

    <c:if test="${bidTotalPages > 1}">
        <nav aria-label="Bid history pagination">
            <ul class="pagination">
                <li class="page-item ${bidPage <= 1 ? 'disabled' : ''}">
                    <a class="page-link"
                       href="?auctionId=${auctionId}&amp;page=${bidPage - 1}&amp;size=${bidPageSize}">
                        Previous
                    </a>
                </li>
                <c:forEach var="p" begin="1" end="${bidTotalPages}">
                    <li class="page-item ${p == bidPage ? 'active' : ''}">
                        <a class="page-link"
                           href="?auctionId=${auctionId}&amp;page=${p}&amp;size=${bidPageSize}">${p}</a>
                    </li>
                </c:forEach>
                <li class="page-item ${bidPage >= bidTotalPages ? 'disabled' : ''}">
                    <a class="page-link"
                       href="?auctionId=${auctionId}&amp;page=${bidPage + 1}&amp;size=${bidPageSize}">
                        Next
                    </a>
                </li>
            </ul>
        </nav>
        <p class="text-muted small">
            Page ${bidPage} of ${bidTotalPages}
            (${bidTotalCount} bid<c:if test="${bidTotalCount != 1}">s</c:if> total)
        </p>
    </c:if>

    <a href="${pageContext.request.contextPath}/auction/${auctionId}#bid-history"
       class="btn btn-outline-secondary btn-sm mt-2">
        <i class="bi bi-arrow-left me-1"></i>Back to auction
    </a>
</div>

<script src="https://cdn.jsdelivr.net/npm/bootstrap@5.3.3/dist/js/bootstrap.bundle.min.js"></script>
</body>
</html>
