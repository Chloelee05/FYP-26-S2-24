import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { act, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';

// The card fetches admin-editable CTA copy on mount; stub it so tests assert the
// countdown behaviour rather than the network.
vi.mock('../api/auction', () => ({
  getLandingContent: () => Promise.resolve({ data: {} }),
}));

const { default: AuctionCard } = await import('./AuctionCard');

const NOW = new Date('2026-08-01T12:00:00Z').getTime();
const HOUR = 3_600_000;

const card = (endsInMs, over = {}) => render(
  <MemoryRouter>
    <AuctionCard auction={{
      auctionId: 7,
      title: 'Vintage camera',
      currentPrice: 250,
      endDate: new Date(NOW + endsInMs).toISOString(),
      sellerUsername: 'ada',
      category: 'Cameras',
      ...over,
    }} />
  </MemoryRouter>,
);

describe('AuctionCard', () => {
  beforeEach(() => vi.useFakeTimers({ now: NOW }));
  afterEach(() => vi.useRealTimers());

  it('shows the listing title and current price', () => {
    card(3 * HOUR);
    expect(screen.getByText('Vintage camera')).toBeInTheDocument();
    expect(screen.getByText('$250.00')).toBeInTheDocument();
  });

  it('counts down while the auction is open', () => {
    card(2 * HOUR + 3 * 60_000 + 4000);
    expect(screen.getByText('2h 3m 4s')).toBeInTheDocument();
  });

  it('reads Ended once the end time has passed', () => {
    card(-1000);
    expect(screen.getByText('Ended')).toBeInTheDocument();
  });

  // The bug this guards: a card left open across the end time used to keep showing a
  // stale countdown until the page was refreshed.
  it('ticks down and flips to Ended in place, without a remount', async () => {
    card(3000);
    expect(screen.getByText('0h 0m 3s')).toBeInTheDocument();

    await act(async () => { vi.advanceTimersByTime(2000); });
    expect(screen.getByText('0h 0m 1s')).toBeInTheDocument();

    await act(async () => { vi.advanceTimersByTime(1000); });
    expect(screen.getByText('Ended')).toBeInTheDocument();
  });

  it('links to the auction detail page', () => {
    card(HOUR);
    const links = screen.getAllByRole('link').map(a => a.getAttribute('href'));
    expect(links).toContain('/auction/7');
  });

  it('falls back to a placeholder when there is no photo', () => {
    card(HOUR);
    expect(screen.getByText('No image')).toBeInTheDocument();
  });

  it('renders the photo with lazy loading and the title as alt text', () => {
    card(HOUR, { thumbnailUrl: '/uploads/cam.png' });
    const img = screen.getByAltText('Vintage camera');
    expect(img).toHaveAttribute('loading', 'lazy');
  });

  it('only shows the "why this?" panel for a recommended card', () => {
    const { unmount } = card(HOUR);
    expect(screen.queryByRole('button', { expanded: false })).not.toBeInTheDocument();
    unmount();

    card(HOUR, { why: { reason: 'Similar to items you bid on', keywords: ['camera'], clickCount: 3 } });
    expect(screen.getByText('Similar to items you bid on')).toBeInTheDocument();
  });

  it('shows the re-ranker score and the component behind it once expanded', async () => {
    card(HOUR, {
      why: {
        reason: 'Buyers with similar taste are watching this',
        score: 0.7143,
        dominantComponent: 'UBCF',
        keywords: [],
        clickCount: 0,
      },
    });

    // The panel is collapsed by default so the card does not become a wall of text.
    expect(screen.queryByText(/Match score/)).not.toBeInTheDocument();
    await act(async () => { screen.getByRole('button', { expanded: false }).click(); });

    expect(screen.getByText('0.71')).toBeInTheDocument();
    expect(screen.getByText(/mostly similar taste/)).toBeInTheDocument();
  });

  it('omits the score on a card the re-ranker never scored', async () => {
    card(HOUR, { why: { reason: 'Trending — collecting the most bids this week', keywords: [] } });
    await act(async () => { screen.getByRole('button', { expanded: false }).click(); });

    expect(screen.queryByText(/Match score/)).not.toBeInTheDocument();
  });
});
