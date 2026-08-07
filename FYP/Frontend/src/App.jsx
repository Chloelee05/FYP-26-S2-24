/*
 * App.jsx is the router for the whole AuctionHub front end and the single place where
 * access control is declared. Three tiers of access exist here:
 *   1. Public, reachable by an unregistered visitor: the landing page, search, an auction
 *      detail page, a public seller profile, the login/register/password flows and the
 *      legal pages. A guest can browse and look at auctions but cannot bid.
 *   2. Signed in, wrapped in <ProtectedRoute>: profile, account settings, watchlist,
 *      bidding history, purchases, messages and support. No session means a redirect
 *      to /login.
 *   3. Capability or role gated: <ProtectedRoute requireSeller> for the seller tools and
 *      <ProtectedRoute roles={['ADMIN']}> for the admin console.
 * AuthProvider wraps everything so useAuth() can read the session anywhere, and every
 * page under MainLayout gets the navbar, footer and support chat widget.
 */
import { lazy, Suspense } from 'react';
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { AuthProvider } from './context/AuthContext';
import Navbar from './components/Navbar';
import Footer from './components/Footer';
import SupportChatWidget from './components/SupportChatWidget';
import ProtectedRoute from './components/ProtectedRoute';
import ErrorBoundary from './components/ErrorBoundary';
import ScrollToTop from './components/ScrollToTop';

import Home from './pages/Home';
import Login from './pages/Login';
import Register from './pages/Register';
import ForgotPassword from './pages/ForgotPassword';
import ResetPassword from './pages/ResetPassword';
import TwoFactorLogin from './pages/TwoFactorLogin';
import Search from './pages/Search';
import AuctionDetail from './pages/AuctionDetail';
import UserProfile from './pages/UserProfile';
import BiddingHistory from './pages/BiddingHistory';
import RateSeller from './pages/RateSeller';
import Watchlist from './pages/Watchlist';
import SellerProfilePublic from './pages/SellerProfilePublic';
import AccountSettings from './pages/AccountSettings';
import Messages from './pages/Messages';
import SupportChat from './pages/SupportChat';
import LegalPage from './pages/LegalPage';
import NotFound from './pages/NotFound';

// The seller tools and admin console are only opened by signed-in accounts, so they
// are split out of the main bundle rather than shipped to every anonymous visitor.
const MyPurchases = lazy(() => import('./pages/MyPurchases'));
const MySales = lazy(() => import('./pages/MySales'));
const SellerDashboard = lazy(() => import('./pages/seller/SellerDashboard'));
const MyListings = lazy(() => import('./pages/seller/MyListings'));
const CreateAuction = lazy(() => import('./pages/seller/CreateAuction'));
const EditAuction = lazy(() => import('./pages/seller/EditAuction'));

const AdminLayout = lazy(() => import('./pages/admin/AdminLayout'));
const AdminDashboard = lazy(() => import('./pages/admin/AdminDashboard'));
const AdminUsers = lazy(() => import('./pages/admin/AdminUsers'));
const AdminListings = lazy(() => import('./pages/admin/AdminListings'));
const AdminAnalytics = lazy(() => import('./pages/admin/AdminAnalytics'));
const AdminDatabase = lazy(() => import('./pages/admin/AdminDatabase'));
const AdminCategories = lazy(() => import('./pages/admin/AdminCategories'));
const AdminLandingContent = lazy(() => import('./pages/admin/AdminLandingContent'));
const AdminReports = lazy(() => import('./pages/admin/AdminReports'));
const AdminReviews = lazy(() => import('./pages/admin/AdminReviews'));
const AdminOrders = lazy(() => import('./pages/admin/AdminOrders'));
const AdminChat = lazy(() => import('./pages/admin/AdminChat'));

// Terms, privacy, payments, cookies and ad choice all render from one LegalPage component
// that picks its text from the current path, so they share a single route definition.
const LEGAL_PATHS = ['/terms', '/privacy', '/payments', '/cookies', '/adchoice'];

// Skeleton shown while a lazily imported route chunk is still downloading.
function RouteFallback() {
  return (
    <div className="max-w-5xl mx-auto px-4 py-10 space-y-4" aria-busy="true" aria-label="Loading">
      <div className="skeleton h-9 w-56" />
      <div className="skeleton h-4 w-80" />
      <div className="skeleton h-64 w-full rounded-2xl" />
    </div>
  );
}

// Shared shell for the public and member pages. The admin console is deliberately outside
// this layout because it has its own sidebar. ErrorBoundary sits inside <main> so a crash
// in one page still leaves the navbar usable for navigating away.
function MainLayout({ children }) {
  return (
    <div className="flex flex-col min-h-screen">
      <Navbar />
      <main className="flex-1">
        <ErrorBoundary>{children}</ErrorBoundary>
      </main>
      <Footer />
      <SupportChatWidget />
    </div>
  );
}

function App() {
  // The build can be served from a sub path (the WAR deploys under a context path), so the
  // router basename comes from Vite's BASE_URL with the trailing slash stripped.
  const basename = (import.meta.env.BASE_URL || '/').replace(/\/$/, '') || undefined;

  return (
    <BrowserRouter basename={basename}>
      <AuthProvider>
        <ScrollToTop />
        <ErrorBoundary>
          <Suspense fallback={<RouteFallback />}>
            <Routes>
              {/* Auth pages – no navbar */}
              <Route path="/login" element={<Login />} />
              <Route path="/register" element={<Register />} />
              {/* Step 1 asks for the email, step 2 takes the emailed code plus the new password. */}
              <Route path="/forgot-password" element={<ForgotPassword />} />
              <Route path="/reset-password" element={<ResetPassword />} />
              <Route path="/2fa-verify" element={<TwoFactorLogin />} />

              {/* Admin, own sidebar layout. The guard is on the parent route, so every
                  nested admin page inherits the ADMIN role requirement and a non admin
                  account is bounced to the landing page before any admin page mounts. */}
              <Route path="/admin" element={
                <ProtectedRoute roles={['ADMIN']}>
                  <AdminLayout />
                </ProtectedRoute>
              }>
                <Route index element={<AdminDashboard />} />
                <Route path="users" element={<AdminUsers />} />
                <Route path="listings" element={<AdminListings />} />
                <Route path="analytics" element={<AdminAnalytics />} />
                <Route path="database" element={<AdminDatabase />} />
                <Route path="categories" element={<AdminCategories />} />
                <Route path="landing-content" element={<AdminLandingContent />} />
                <Route path="reports" element={<AdminReports />} />
                <Route path="reviews" element={<AdminReviews />} />
                <Route path="orders" element={<AdminOrders />} />
                <Route path="chat" element={<AdminChat />} />
              </Route>

              {/* Public pages. No ProtectedRoute wrapper, so a visitor with no account can
                  browse the catalogue and open any auction. Bidding controls on those pages
                  check the session themselves and prompt a guest to sign in. */}
              <Route path="/" element={<MainLayout><Home /></MainLayout>} />
              <Route path="/search" element={<MainLayout><Search /></MainLayout>} />
              <Route path="/auction/:id" element={<MainLayout><AuctionDetail /></MainLayout>} />
              {/* React Router ranks a literal segment above a dynamic one, so /seller/dashboard
                  below still wins over this /seller/:username pattern despite coming later. */}
              <Route path="/seller/:username" element={<MainLayout><SellerProfilePublic /></MainLayout>} />

              {/* Member pages. ProtectedRoute with no props means any signed in account. */}
              <Route path="/profile" element={<MainLayout><ProtectedRoute><UserProfile /></ProtectedRoute></MainLayout>} />
              <Route path="/profile/settings" element={<MainLayout><ProtectedRoute><AccountSettings /></ProtectedRoute></MainLayout>} />
              {/* Editing the profile, the password and 2FA are all tabs of Account settings
                  now. Keep the old standalone paths working for existing bookmarks. */}
              <Route path="/profile/edit" element={<Navigate to="/profile/settings" replace />} />
              <Route path="/profile/change-password" element={<Navigate to="/profile/settings?tab=password" replace />} />
              <Route path="/profile/2fa" element={<Navigate to="/profile/settings?tab=2fa" replace />} />
              <Route path="/bidding-history" element={<MainLayout><ProtectedRoute><BiddingHistory /></ProtectedRoute></MainLayout>} />
              {/* Orders split by side of the deal: buying is open to every member,
                  selling reuses the same capability gate as the rest of the seller tools. */}
              <Route path="/purchases" element={<MainLayout><ProtectedRoute><MyPurchases /></ProtectedRoute></MainLayout>} />
              <Route path="/sales" element={<MainLayout><ProtectedRoute requireSeller><MySales /></ProtectedRoute></MainLayout>} />
              <Route path="/rate-seller/:auctionId" element={<MainLayout><ProtectedRoute><RateSeller /></ProtectedRoute></MainLayout>} />
              <Route path="/watchlist" element={<MainLayout><ProtectedRoute><Watchlist /></ProtectedRoute></MainLayout>} />
              <Route path="/support" element={<MainLayout><ProtectedRoute><SupportChat /></ProtectedRoute></MainLayout>} />
              <Route path="/messages" element={<MainLayout><ProtectedRoute><Messages /></ProtectedRoute></MainLayout>} />

              {/* Seller tools. requireSeller is a capability check on user.canSell, not a
                  separate role, because one account both buys and sells. An account without
                  the capability is shown the enable selling gate instead of being redirected. */}
              <Route path="/seller/dashboard" element={<MainLayout><ProtectedRoute requireSeller><SellerDashboard /></ProtectedRoute></MainLayout>} />
              <Route path="/seller/listings" element={<MainLayout><ProtectedRoute requireSeller><MyListings /></ProtectedRoute></MainLayout>} />
              <Route path="/seller/create" element={<MainLayout><ProtectedRoute requireSeller><CreateAuction /></ProtectedRoute></MainLayout>} />
              <Route path="/seller/auction/:id/edit" element={<MainLayout><ProtectedRoute requireSeller><EditAuction /></ProtectedRoute></MainLayout>} />

              {/* Policy pages linked from the footer and the registration form */}
              {LEGAL_PATHS.map(path => (
                <Route key={path} path={path} element={<MainLayout><LegalPage /></MainLayout>} />
              ))}

              {/* Anything else */}
              <Route path="*" element={<MainLayout><NotFound /></MainLayout>} />
            </Routes>
          </Suspense>
        </ErrorBoundary>
      </AuthProvider>
    </BrowserRouter>
  );
}

export default App;
