import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { AuthProvider } from './context/AuthContext';
import Navbar from './components/Navbar';
import Footer from './components/Footer';
import ProtectedRoute from './components/ProtectedRoute';

import Home from './pages/Home';
import Login from './pages/Login';
import Register from './pages/Register';
import ForgotPassword from './pages/ForgotPassword';
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

import SellerDashboard from './pages/seller/SellerDashboard';
import CreateAuction from './pages/seller/CreateAuction';
import EditAuction from './pages/seller/EditAuction';

import AdminLayout from './pages/admin/AdminLayout';
import AdminDashboard from './pages/admin/AdminDashboard';
import AdminUsers from './pages/admin/AdminUsers';
import AdminListings from './pages/admin/AdminListings';
import AdminAnalytics from './pages/admin/AdminAnalytics';
import AdminCategories from './pages/admin/AdminCategories';
import AdminReports from './pages/admin/AdminReports';

function MainLayout({ children }) {
  return (
    <div className="flex flex-col min-h-screen">
      <Navbar />
      <main className="flex-1">{children}</main>
      <Footer />
    </div>
  );
}

function App() {
  return (
    <BrowserRouter>
      <AuthProvider>
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
            <Route path="categories" element={<AdminCategories />} />
            <Route path="reports" element={<AdminReports />} />
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

          <Route path="/seller/dashboard" element={<MainLayout><ProtectedRoute roles={['SELLER']}><SellerDashboard /></ProtectedRoute></MainLayout>} />
          <Route path="/seller/create" element={<MainLayout><ProtectedRoute roles={['SELLER']}><CreateAuction /></ProtectedRoute></MainLayout>} />
          <Route path="/seller/auction/:id/edit" element={<MainLayout><ProtectedRoute roles={['SELLER']}><EditAuction /></ProtectedRoute></MainLayout>} />
        </Routes>
      </AuthProvider>
    </BrowserRouter>
  );
}

export default App;
