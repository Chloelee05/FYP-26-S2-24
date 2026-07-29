import { lazy, Suspense } from 'react';
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { AuthProvider } from './context/AuthContext';
import Navbar from './components/Navbar';
import Footer from './components/Footer';
import SupportChatWidget from './components/SupportChatWidget';
import ProtectedRoute from './components/ProtectedRoute';
import ErrorBoundary from './components/ErrorBoundary';

import Home from './pages/Home';
import Login from './pages/Login';
import Register from './pages/Register';
import ResetPassword from './pages/ResetPassword';
import TwoFactorLogin from './pages/TwoFactorLogin';
import TwoFactorSettings from './pages/TwoFactorSettings';
import Search from './pages/Search';
import AuctionDetail from './pages/AuctionDetail';
import UserProfile from './pages/UserProfile';
import EditProfile from './pages/EditProfile';
import ChangePassword from './pages/ChangePassword';
import BiddingHistory from './pages/BiddingHistory';
import RateSeller from './pages/RateSeller';
import Watchlist from './pages/Watchlist';
import SellerProfilePublic from './pages/SellerProfilePublic';
import AccountSettings from './pages/AccountSettings';
import Messages from './pages/Messages';
import SupportChat from './pages/SupportChat';
import LegalPage from './pages/LegalPage';
import NotFound from './pages/NotFound';

// Seller and admin consoles are only reachable by those roles, so they are split
// out of the main bundle rather than shipped to every anonymous visitor.
const SellerDashboard = lazy(() => import('./pages/seller/SellerDashboard'));
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

const LEGAL_PATHS = ['/terms', '/privacy', '/payments', '/cookies', '/adchoice'];

function RouteFallback() {
  return (
    <div className="max-w-5xl mx-auto px-4 py-10 space-y-4" aria-busy="true" aria-label="Loading">
      <div className="skeleton h-9 w-56" />
      <div className="skeleton h-4 w-80" />
      <div className="skeleton h-64 w-full rounded-2xl" />
    </div>
  );
}

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
  const basename = (import.meta.env.BASE_URL || '/').replace(/\/$/, '') || undefined;

  return (
    <BrowserRouter basename={basename}>
      <AuthProvider>
        <ErrorBoundary>
          <Suspense fallback={<RouteFallback />}>
            <Routes>
              {/* Auth pages – no navbar */}
              <Route path="/login" element={<Login />} />
              <Route path="/register" element={<Register />} />
              <Route path="/forgot-password" element={<Navigate to="/reset-password" replace />} />
              <Route path="/reset-password" element={<ResetPassword />} />
              <Route path="/2fa-verify" element={<TwoFactorLogin />} />

              {/* Admin – own sidebar layout */}
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

              {/* All other pages with Navbar + Footer */}
              <Route path="/" element={<MainLayout><Home /></MainLayout>} />
              <Route path="/search" element={<MainLayout><Search /></MainLayout>} />
              <Route path="/auction/:id" element={<MainLayout><AuctionDetail /></MainLayout>} />
              <Route path="/seller/:username" element={<MainLayout><SellerProfilePublic /></MainLayout>} />

              <Route path="/profile" element={<MainLayout><ProtectedRoute><UserProfile /></ProtectedRoute></MainLayout>} />
              <Route path="/profile/edit" element={<MainLayout><ProtectedRoute><EditProfile /></ProtectedRoute></MainLayout>} />
              <Route path="/profile/change-password" element={<MainLayout><ProtectedRoute><ChangePassword /></ProtectedRoute></MainLayout>} />
              <Route path="/profile/2fa" element={<MainLayout><ProtectedRoute><TwoFactorSettings /></ProtectedRoute></MainLayout>} />
              <Route path="/profile/settings" element={<MainLayout><ProtectedRoute><AccountSettings /></ProtectedRoute></MainLayout>} />
              <Route path="/bidding-history" element={<MainLayout><ProtectedRoute><BiddingHistory /></ProtectedRoute></MainLayout>} />
              <Route path="/rate-seller/:auctionId" element={<MainLayout><ProtectedRoute><RateSeller /></ProtectedRoute></MainLayout>} />
              <Route path="/watchlist" element={<MainLayout><ProtectedRoute><Watchlist /></ProtectedRoute></MainLayout>} />
              <Route path="/support" element={<MainLayout><ProtectedRoute><SupportChat /></ProtectedRoute></MainLayout>} />
              <Route path="/messages" element={<MainLayout><ProtectedRoute><Messages /></ProtectedRoute></MainLayout>} />

              <Route path="/seller/dashboard" element={<MainLayout><ProtectedRoute requireSeller><SellerDashboard /></ProtectedRoute></MainLayout>} />
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
