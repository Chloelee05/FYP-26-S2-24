<%@ page contentType="text/html;charset=UTF-8" language="java" %>
<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
<jsp:include page="/common/header.jsp">
    <jsp:param name="title" value="Admin Dashboard - AuctionHub"/>
</jsp:include>

<h2 class="mb-4"><i class="bi bi-speedometer2 text-primary"></i> Admin Dashboard</h2>

<!-- Stats Cards -->
<div class="row g-4 mb-4">
    <div class="col-md-4">
        <div class="card stat-card bg-primary text-white">
            <div class="card-body d-flex justify-content-between align-items-center">
                <div>
                    <div class="stat-value">${totalUsers}</div>
                    <div>Total Users</div>
                </div>
                <div class="stat-icon"><i class="bi bi-people"></i></div>
            </div>
            <div class="card-footer bg-transparent border-0">
                <a href="${pageContext.request.contextPath}/admin/users" class="text-white text-decoration-none">
                    Manage Users <i class="bi bi-arrow-right"></i>
                </a>
            </div>
        </div>
    </div>
    <div class="col-md-4">
        <div class="card stat-card bg-success text-white">
            <div class="card-body d-flex justify-content-between align-items-center">
                <div>
                    <div class="stat-value">${activeAuctions}</div>
                    <div>Active Auctions</div>
                </div>
                <div class="stat-icon"><i class="bi bi-hammer"></i></div>
            </div>
            <div class="card-footer bg-transparent border-0">
                <a href="${pageContext.request.contextPath}/product/list" class="text-white text-decoration-none">
                    View Auctions <i class="bi bi-arrow-right"></i>
                </a>
            </div>
        </div>
    </div>
    <div class="col-md-4">
        <div class="card stat-card bg-info text-white">
            <div class="card-body d-flex justify-content-between align-items-center">
                <div>
                    <div class="stat-value">${totalProducts}</div>
                    <div>Total Products</div>
                </div>
                <div class="stat-icon"><i class="bi bi-box-seam"></i></div>
            </div>
            <div class="card-footer bg-transparent border-0">
                <span class="text-white">Listed items</span>
            </div>
        </div>
    </div>
</div>

<!-- Quick Actions -->
<div class="card border-0 shadow-sm" style="border-radius:12px;">
    <div class="card-body">
        <h5><i class="bi bi-lightning"></i> Quick Actions</h5>
        <div class="d-flex gap-2 flex-wrap">
            <a href="${pageContext.request.contextPath}/admin/users" class="btn btn-outline-primary">
                <i class="bi bi-people"></i> Manage Users
            </a>
            <a href="${pageContext.request.contextPath}/product/list" class="btn btn-outline-success">
                <i class="bi bi-grid"></i> View Auctions
            </a>
        </div>
    </div>
</div>

<jsp:include page="/common/footer.jsp"/>
