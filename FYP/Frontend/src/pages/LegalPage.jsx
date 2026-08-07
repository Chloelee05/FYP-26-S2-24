/*
 * One component serving five public policy routes: /terms, /privacy, /payments, /cookies and
 * /adchoice, all listed in LEGAL_PATHS in App.jsx and linked from the footer and the
 * registration form. No API calls; the text is the DOCS map below, keyed by pathname, and an
 * unknown path renders nothing so the "*" route can take over.
 * The privacy and cookies entries are the user facing statement of the PDPA behaviour the
 * code implements: encrypted personal fields, hashed passwords, masked bidder identities,
 * a tab scoped session token, and deletion that erases personal data while keeping
 * anonymised transaction records.
 */
import { Link, useLocation } from 'react-router-dom';
import { FileText, ShieldCheck, CreditCard, Cookie, Megaphone, ChevronLeft } from 'lucide-react';

/**
 * Static policy pages behind the footer/registration links.
 *
 * These are course-project placeholders, not reviewed legal copy — the point is
 * that every link in the footer resolves to a real page instead of a blank screen.
 */
const DOCS = {
  '/terms': {
    icon: FileText,
    title: 'User Agreement',
    intro: 'The terms you accept when you create an AuctionHub account.',
    sections: [
      {
        heading: 'Accounts',
        body: 'You must provide accurate registration details and keep your credentials confidential. One person may hold one buyer account and one seller account. Accounts found to be impersonating others or evading a ban may be suspended.',
      },
      {
        heading: 'Bidding is binding',
        body: 'Placing a bid is a commitment to buy at that price if you win. Standard auctions accept any bid above the current highest bid. Dutch auctions sell to the first buyer who accepts the displayed price. Blind auctions accept one sealed bid per buyer, revealed only when the auction closes.',
      },
      {
        heading: 'Seller obligations',
        body: 'Sellers must describe items accurately, hold stock they can ship, and complete the sale with the winning bidder. Cancelling an auction that already has bids is restricted.',
      },
      {
        heading: 'Prohibited listings',
        body: 'Counterfeit goods, illegal items, and listings that misrepresent condition are removed. Repeat violations result in account suspension.',
      },
      {
        heading: 'Disputes',
        body: 'Buyers may open a refund request against an order. If the seller declines and the issue stands, escalate through Contact Admin and a moderator will review it.',
      },
    ],
  },
  '/privacy': {
    icon: ShieldCheck,
    title: 'Privacy Notice',
    intro: 'What personal data AuctionHub collects, why, and what you can do about it.',
    sections: [
      {
        heading: 'What we collect',
        body: 'Account details (username, email, and optionally phone and address), your bidding and browsing activity, orders and payment method metadata, plus support messages you send us.',
      },
      {
        heading: 'Why we collect it',
        body: 'To run auctions and settle orders, to show you relevant recommendations, to detect fraud and abuse, and to respond to support requests.',
      },
      {
        heading: 'How it is protected',
        body: 'Personal fields are stored encrypted, passwords are hashed and never recoverable in plain text, and two-factor authentication is available on every account from Account Settings.',
      },
      {
        heading: 'What other users see',
        body: 'Your username and seller rating are public on listings. Bidder identities are masked in bid history. Your email, phone, and address are never shown to other users.',
      },
      {
        heading: 'Your rights',
        body: 'You can view and correct your data from Account Settings, and delete your account entirely. Deletion removes your personal data while retaining the anonymised transaction records we are required to keep.',
      },
    ],
  },
  '/payments': {
    icon: CreditCard,
    title: 'Payments Terms of Use',
    intro: 'How payments, fees and refunds work on AuctionHub.',
    sections: [
      {
        heading: 'Prototype notice',
        body: 'This is a course project. Payments are simulated end to end — no real card is charged, no money moves, and there is no in-app wallet or withdrawal. Stored payment methods hold display metadata only.',
      },
      {
        heading: 'Paying for an order',
        body: 'Winning an auction creates an order awaiting payment. Pay from your profile using a saved payment method, then confirm receipt once the item arrives.',
      },
      {
        heading: 'Seller fees',
        body: 'A platform commission is deducted from completed sales, and featuring a listing on the homepage carries a flat promotional fee. Both appear in your seller earnings summary.',
      },
      {
        heading: 'Refunds',
        body: 'Refund requests go to the seller first. An approved refund cancels the order. Do not confirm receipt for an item you have not received — confirming completes the order.',
      },
    ],
  },
  '/cookies': {
    icon: Cookie,
    title: 'Cookies',
    intro: 'How AuctionHub uses cookies and browser storage.',
    sections: [
      {
        heading: 'Session',
        body: 'A session cookie keeps you signed in as you move between pages. Your sign-in token is also held in tab-scoped session storage, which is what lets you be signed in as different accounts in different tabs.',
      },
      {
        heading: 'Preferences',
        body: 'Local storage remembers small conveniences such as the email address to prefill on the sign-in form when you ask us to.',
      },
      {
        heading: 'No advertising cookies',
        body: 'We set no third-party advertising or cross-site tracking cookies. Recommendations are computed from your activity on AuctionHub alone.',
      },
      {
        heading: 'Clearing them',
        body: 'Clearing cookies and site data in your browser signs you out and resets stored preferences. The site continues to work.',
      },
    ],
  },
  '/adchoice': {
    icon: Megaphone,
    title: 'AdChoice',
    intro: 'How promoted listings work and how personalisation is applied.',
    sections: [
      {
        heading: 'Featured listings',
        body: 'Sellers can pay to feature a listing on the homepage for a fixed period. Featured listings appear in their own clearly labelled section and are never mixed into search results unlabelled.',
      },
      {
        heading: 'Recommendations',
        body: 'The "Recommended for You" section is generated from auctions you and similar buyers have bid on or watched. It is not paid placement.',
      },
      {
        heading: 'Opting out',
        body: 'Dismiss any recommendation with the close button on the card and it stays hidden. Browse while signed out to see non-personalised trending listings instead.',
      },
    ],
  },
};

export default function LegalPage() {
  const { pathname } = useLocation();
  const doc = DOCS[pathname];

  // Only the five paths registered in App.jsx can reach here, so an unmatched path means a
  // route was added without its copy. Rendering nothing is safer than crashing on undefined.
  if (!doc) return null;

  const { icon: Icon, title, intro, sections } = doc;

  return (
    <div className="max-w-3xl mx-auto px-4 py-10">
      <Link
        to="/"
        className="inline-flex items-center gap-1 text-sm font-medium text-ink-500 hover:text-primary-600 transition-colors mb-6"
      >
        <ChevronLeft size={16} /> Back to home
      </Link>

      <div className="flex items-start gap-4 mb-8">
        <span className="grid place-items-center w-12 h-12 rounded-2xl bg-primary-50 text-primary-600 shrink-0">
          <Icon size={22} />
        </span>
        <div>
          <h1 className="page-title">{title}</h1>
          <p className="page-subtitle">{intro}</p>
        </div>
      </div>

      <div className="card p-6 sm:p-8">
        <div className="alert-info mb-8 text-xs">
          <span>
            AuctionHub is a final-year course project. This page is illustrative placeholder
            content and is not legal advice.
          </span>
        </div>

        <div className="space-y-7">
          {sections.map(({ heading, body }, i) => (
            <section key={heading}>
              <h2 className="section-title text-base mb-2">
                <span className="text-ink-300 tabular-nums mr-2">{String(i + 1).padStart(2, '0')}</span>
                {heading}
              </h2>
              <p className="text-sm text-ink-600 leading-relaxed">{body}</p>
            </section>
          ))}
        </div>

        <p className="text-xs text-ink-400 mt-8 pt-6 border-t border-ink-100">
          Last updated 27 July 2026. Questions? Reach the team through Contact Admin.
        </p>
      </div>
    </div>
  );
}
