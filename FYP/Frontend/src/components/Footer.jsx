import { Link } from 'react-router-dom';

export default function Footer() {
  return (
    <footer className="bg-[#1e2d5a] text-white mt-auto">
      <div className="max-w-7xl mx-auto px-4 py-12 grid md:grid-cols-2 gap-8">
        <div>
          <div className="flex items-center gap-2 text-xl font-bold mb-3">
            <span>⚒</span> AuctionHub
          </div>
          <p className="text-blue-200 text-sm">Bid Smart, Buy Right</p>
        </div>
        <div className="md:text-right">
          <p className="text-sm text-blue-200 mb-2">Stay Connected:</p>
          <div className="flex md:justify-end gap-4">
            <a href="#" className="hover:text-blue-300 transition-colors">Facebook</a>
            <a href="#" className="hover:text-blue-300 transition-colors">Instagram</a>
            <a href="#" className="hover:text-blue-300 transition-colors">TikTok</a>
          </div>
          <p className="text-sm text-blue-200 font-bold mt-3">About us</p>
        </div>
      </div>
      <div className="border-t border-blue-800 py-4 text-center text-xs text-blue-300">
        Copyright © 2026 AuctionHub Inc. All Right Reserved.{' '}
        <Link to="/terms" className="underline">User Agreement</Link>,{' '}
        <Link to="/privacy" className="underline">Privacy</Link>,{' '}
        <Link to="/payments" className="underline">Payments Terms of use</Link>,{' '}
        <Link to="/cookies" className="underline">Cookies</Link> and{' '}
        <Link to="/adchoice" className="underline">AdChoice</Link>
      </div>
    </footer>
  );
}
