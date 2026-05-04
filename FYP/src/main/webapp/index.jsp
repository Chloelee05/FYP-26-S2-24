<%@ page contentType="text/html;charset=UTF-8" language="java" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<c:set var="ctx" value="${pageContext.request.contextPath}"/>
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>AuctionHub — Bid Smart, Buy Right</title>
    <link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/bootstrap@5.3.3/dist/css/bootstrap.min.css">
    <link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/bootstrap-icons@1.11.3/font/bootstrap-icons.min.css">
    <link rel="stylesheet" href="${ctx}/css/home.css">
</head>
<body class="bg-white">

<%@ include file="/WEB-INF/includes/home-navbar.jsp" %>

<c:if test="${not empty param.q}">
    <div class="container py-2">
        <div class="alert alert-light border small mb-0" role="status">
            Search results for “<strong><c:out value="${param.q}"/></strong>” — listing UI is a placeholder until auction search is wired to the database (SCRUM-108+).
        </div>
    </div>
</c:if>

<section class="home-hero">
    <div class="container">
        <div class="row align-items-center">
            <div class="col-lg-6">
                <h1>Bid Smart, Buy Right</h1>
                <p class="lead mt-3">List your items, bid on your favorites, and find the perfect deal with ease.</p>
                <a href="#trending" class="btn btn-explore mt-3">Explore more</a>
            </div>
            <div class="col-lg-6">
                <div class="home-hero-collage mt-4 mt-lg-0" aria-hidden="true">
                    <img src="https://images.unsplash.com/photo-1516035069371-29a1b244cc32?w=300&q=80" alt="">
                    <img src="https://images.unsplash.com/photo-1606220588913-b3aacb4d2f46?w=300&q=80" alt="">
                    <img src="https://images.unsplash.com/photo-1556656793-08538906a9f8?w=300&q=80" alt="">
                    <img src="https://images.unsplash.com/photo-1523275335684-37898b6baf30?w=300&q=80" alt="">
                    <img src="https://images.unsplash.com/photo-1492144534655-ae79c964c9d7?w=300&q=80" alt="">
                    <img src="https://images.unsplash.com/photo-1568605114967-8130f3a36994?w=300&q=80" alt="">
                </div>
            </div>
        </div>
    </div>
</section>

<section class="py-5" id="categories">
    <div class="container">
        <h2 class="home-section-title">Popular Categories</h2>
        <div class="row row-cols-2 row-cols-sm-3 row-cols-md-5 g-3 justify-content-center text-center">
            <div class="col">
                <a href="#trending" class="home-cat-circle w-100 text-decoration-none" data-home-category="electronics">
                    <div class="img-wrap mx-auto">
                        <img src="https://images.unsplash.com/photo-1505740420928-5e560c06d30e?w=200&q=80" alt="" loading="lazy">
                    </div>
                    <span>Headphone</span>
                </a>
            </div>
            <div class="col">
                <a href="#trending" class="home-cat-circle w-100 text-decoration-none" data-home-category="electronics">
                    <div class="img-wrap mx-auto">
                        <img src="https://images.unsplash.com/photo-1511707171634-5f897ff02aa9?w=200&q=80" alt="" loading="lazy">
                    </div>
                    <span>Phone</span>
                </a>
            </div>
            <div class="col">
                <a href="#trending" class="home-cat-circle w-100 text-decoration-none" data-home-category="property">
                    <div class="img-wrap mx-auto">
                        <img src="https://images.unsplash.com/photo-1568605114967-8130f3a36994?w=200&q=80" alt="" loading="lazy">
                    </div>
                    <span>Property</span>
                </a>
            </div>
            <div class="col">
                <a href="#trending" class="home-cat-circle w-100 text-decoration-none" data-home-category="automotive">
                    <div class="img-wrap mx-auto">
                        <img src="https://images.unsplash.com/photo-1492144534655-ae79c964c9d7?w=200&q=80" alt="" loading="lazy">
                    </div>
                    <span>Car</span>
                </a>
            </div>
            <div class="col">
                <a href="#trending" class="home-cat-circle w-100 text-decoration-none" data-home-category="watches">
                    <div class="img-wrap mx-auto">
                        <img src="https://images.unsplash.com/photo-1524592094714-0f0654e20314?w=200&q=80" alt="" loading="lazy">
                    </div>
                    <span>Watch</span>
                </a>
            </div>
        </div>
    </div>
</section>

<section class="pb-5" id="trending">
    <div class="container">
        <h2 class="home-section-title">Trending Auction</h2>
        <p class="text-muted small mb-4">Placeholder grid for a future recommendation engine (SCRUM-108). Countdowns start from page load.</p>
        <div class="row g-4">
            <div class="col-12 col-sm-6 col-lg-3">
                <article class="card auction-card h-100 shadow-sm border-0" data-category="electronics">
                    <img class="card-img-top" src="https://images.unsplash.com/photo-1505740420928-5e560c06d30e?w=400&q=80"
                         alt="Headphones" loading="lazy">
                    <div class="card-body d-flex flex-column">
                        <h3 class="card-title">Noise Cancellation Headphone</h3>
                        <p class="auction-countdown mb-2 text-muted" data-hours="24.84">End in: —</p>
                        <p class="current-bid mb-0">Current Bid</p>
                        <p class="bid-amount">$750.00</p>
                        <a href="#" class="in-detail text-decoration-underline">In Detail</a>
                        <button type="button" class="btn btn-bid mt-auto">BID NOW</button>
                    </div>
                </article>
            </div>
            <div class="col-12 col-sm-6 col-lg-3">
                <article class="card auction-card h-100 shadow-sm border-0" data-category="fashion">
                    <img class="card-img-top" src="https://images.unsplash.com/photo-1542291026-7eec264c27ff?w=400&q=80"
                         alt="Sneakers" loading="lazy">
                    <div class="card-body d-flex flex-column">
                        <h3 class="card-title">Designer Sneakers</h3>
                        <p class="auction-countdown mb-2 text-muted" data-hours="18.2">End in: —</p>
                        <p class="current-bid mb-0">Current Bid</p>
                        <p class="bid-amount">$3,000.00</p>
                        <a href="#" class="in-detail text-decoration-underline">In Detail</a>
                        <button type="button" class="btn btn-bid mt-auto">BID NOW</button>
                    </div>
                </article>
            </div>
            <div class="col-12 col-sm-6 col-lg-3">
                <article class="card auction-card h-100 shadow-sm border-0" data-category="watches">
                    <img class="card-img-top" src="https://images.unsplash.com/photo-1524592094714-0f0654e20314?w=400&q=80"
                         alt="Watch" loading="lazy">
                    <div class="card-body d-flex flex-column">
                        <h3 class="card-title">Luxury Watch</h3>
                        <p class="auction-countdown mb-2 text-muted" data-hours="72.15">End in: —</p>
                        <p class="current-bid mb-0">Current Bid</p>
                        <p class="bid-amount">$31,000.00</p>
                        <a href="#" class="in-detail text-decoration-underline">In Detail</a>
                        <button type="button" class="btn btn-bid mt-auto">BID NOW</button>
                    </div>
                </article>
            </div>
            <div class="col-12 col-sm-6 col-lg-3">
                <article class="card auction-card h-100 shadow-sm border-0" data-category="camera">
                    <img class="card-img-top" src="https://images.unsplash.com/photo-1516035069371-29a1b244cc32?w=400&q=80"
                         alt="Camera" loading="lazy">
                    <div class="card-body d-flex flex-column">
                        <h3 class="card-title">High Pixel Camera</h3>
                        <p class="auction-countdown mb-2 text-muted" data-hours="6.33">End in: —</p>
                        <p class="current-bid mb-0">Current Bid</p>
                        <p class="bid-amount">$4,250.00</p>
                        <a href="#" class="in-detail text-decoration-underline">In Detail</a>
                        <button type="button" class="btn btn-bid mt-auto">BID NOW</button>
                    </div>
                </article>
            </div>
            <div class="col-12 col-sm-6 col-lg-3">
                <article class="card auction-card h-100 shadow-sm border-0" data-category="property">
                    <img class="card-img-top" src="https://images.unsplash.com/photo-1564013799919-ab600027ffc6?w=400&q=80"
                         alt="Home" loading="lazy">
                    <div class="card-body d-flex flex-column">
                        <h3 class="card-title">Coastal Villa</h3>
                        <p class="auction-countdown mb-2 text-muted" data-hours="120">End in: —</p>
                        <p class="current-bid mb-0">Current Bid</p>
                        <p class="bid-amount">$1,250,000.00</p>
                        <a href="#" class="in-detail text-decoration-underline">In Detail</a>
                        <button type="button" class="btn btn-bid mt-auto">BID NOW</button>
                    </div>
                </article>
            </div>
            <div class="col-12 col-sm-6 col-lg-3">
                <article class="card auction-card h-100 shadow-sm border-0" data-category="automotive">
                    <img class="card-img-top" src="https://images.unsplash.com/photo-1503376780353-7e6692767b70?w=400&q=80"
                         alt="Car" loading="lazy">
                    <div class="card-body d-flex flex-column">
                        <h3 class="card-title">Sport Coupe</h3>
                        <p class="auction-countdown mb-2 text-muted" data-hours="48.5">End in: —</p>
                        <p class="current-bid mb-0">Current Bid</p>
                        <p class="bid-amount">$92,500.00</p>
                        <a href="#trending" class="in-detail text-decoration-underline">In Detail</a>
                        <button type="button" class="btn btn-bid mt-auto">BID NOW</button>
                    </div>
                </article>
            </div>
        </div>
    </div>
</section>

<%@ include file="/WEB-INF/includes/home-footer.jsp" %>

<script src="https://cdn.jsdelivr.net/npm/bootstrap@5.3.3/dist/js/bootstrap.bundle.min.js"></script>
<script src="${ctx}/js/home.js"></script>
</body>
</html>
