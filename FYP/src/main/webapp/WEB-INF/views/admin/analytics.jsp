<%@ page contentType="text/html;charset=UTF-8" language="java" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Analytics — Admin</title>
    <link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/bootstrap@5.3.3/dist/css/bootstrap.min.css">
    <link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/bootstrap-icons@1.11.3/font/bootstrap-icons.min.css">
    <link rel="stylesheet" href="${pageContext.request.contextPath}/css/admin.css">
</head>
<body class="admin-body">
<div class="admin-layout">
    <%@ include file="/WEB-INF/includes/admin-sidebar.jspf" %>
    <main class="admin-main flex-grow-1 p-3 p-lg-4">
        <header class="mb-4">
            <h1 class="admin-page-title mb-1">Analytics &amp; Reports</h1>
            <p class="admin-subtitle mb-0">Generate reports and view insights.</p>
        </header>

        <div class="row g-4 mb-4">
            <div class="col-lg-6">
                <div class="admin-table-card p-4 h-100">
                    <h2 class="h6 fw-semibold">User Growth</h2>
                    <p class="small text-muted mb-3">Weekly user registrations for the past 7 weeks</p>
                    <div class="chart-placeholder d-flex align-items-end justify-content-around gap-1 px-2 pb-2">
                        <c:forEach begin="1" end="7" varStatus="st">
                            <div class="text-center small text-muted">
                                <div class="bg-primary rounded opacity-25" style="height:${40 + st.index * 8}px;width:28px;margin:0 auto 4px;"></div>
                                W${st.index + 1}
                            </div>
                        </c:forEach>
                    </div>
                </div>
            </div>
            <div class="col-lg-6">
                <div class="admin-table-card p-4 h-100">
                    <h2 class="h6 fw-semibold">Revenue Trends</h2>
                    <p class="small text-muted mb-3">Weekly revenue for the past 7 weeks</p>
                    <div class="chart-placeholder d-flex align-items-end justify-content-around gap-1 px-2 pb-2">
                        <c:forEach begin="1" end="7" varStatus="st">
                            <div class="text-center small text-muted">
                                <div class="bg-success rounded opacity-25" style="height:${50 + st.index * 5}px;width:28px;margin:0 auto 4px;"></div>
                                W${st.index + 1}
                            </div>
                        </c:forEach>
                    </div>
                </div>
            </div>
        </div>

        <section>
            <h2 class="h6 fw-semibold mb-3">Generate Reports</h2>
            <div class="row g-3">
                <div class="col-md-4">
                    <div class="admin-table-card p-3 report-card border h-100" data-report-card data-report-title="User Activity Report">
                        <i class="bi bi-file-earmark-text text-primary fs-3"></i>
                        <div class="fw-semibold mt-2">User Activity Report</div>
                        <div class="small text-muted">Export user statistics</div>
                    </div>
                </div>
                <div class="col-md-4">
                    <div class="admin-table-card p-3 report-card border h-100" data-report-card data-report-title="Revenue Report">
                        <i class="bi bi-file-earmark-bar-graph text-success fs-3"></i>
                        <div class="fw-semibold mt-2">Revenue Report</div>
                        <div class="small text-muted">Financial analytics</div>
                    </div>
                </div>
                <div class="col-md-4">
                    <div class="admin-table-card p-3 report-card border h-100" data-report-card data-report-title="Moderation Report">
                        <i class="bi bi-file-earmark-ruled fs-3" style="color:#6f42c1;"></i>
                        <div class="fw-semibold mt-2">Moderation Report</div>
                        <div class="small text-muted">Flags and bans summary</div>
                    </div>
                </div>
            </div>
        </section>

        <!-- SCRUM-181 responsive checks (DevTools): sidebar stacks fully by 991px; tables scroll horizontally; chart row stacks at lg. -->
    </main>
</div>
<script src="https://cdn.jsdelivr.net/npm/bootstrap@5.3.3/dist/js/bootstrap.bundle.min.js"></script>
<script src="${pageContext.request.contextPath}/js/admin-panel.js"></script>
</body>
</html>
