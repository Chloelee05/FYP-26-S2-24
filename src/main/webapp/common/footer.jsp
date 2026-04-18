<%@ page contentType="text/html;charset=UTF-8" language="java" %>
</main>
<footer class="bg-dark text-light py-4 mt-5">
    <div class="container">
        <div class="row">
            <div class="col-md-6">
                <h5><i class="bi bi-hammer"></i> AuctionHub</h5>
                <p class="text-muted">Your trusted online auction platform.</p>
            </div>
            <div class="col-md-3">
                <h6>Quick Links</h6>
                <ul class="list-unstyled">
                    <li><a href="${pageContext.request.contextPath}/" class="text-muted text-decoration-none">Home</a></li>
                    <li><a href="${pageContext.request.contextPath}/product/list" class="text-muted text-decoration-none">Browse</a></li>
                </ul>
            </div>
            <div class="col-md-3">
                <h6>Contact</h6>
                <p class="text-muted mb-0"><i class="bi bi-envelope"></i> support@auctionhub.com</p>
            </div>
        </div>
        <hr class="border-secondary">
        <p class="text-center text-muted mb-0">&copy; 2026 AuctionHub - CSIT-26-S2-12</p>
    </div>
</footer>
<script src="https://cdn.jsdelivr.net/npm/bootstrap@5.3.3/dist/js/bootstrap.bundle.min.js"></script>
<script src="${pageContext.request.contextPath}/js/main.js"></script>
</body>
</html>
