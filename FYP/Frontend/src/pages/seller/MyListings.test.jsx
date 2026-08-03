// My listings carried three of the audit's seller findings at once: it fetched one page of ten
// and rendered no pager, so seller1's 11th and 12th listings were unreachable; it labelled an
// auction nobody bid on "CANCELLED", the same word as one the seller withdrew; and it cancelled
// auctions with no reason, which is why cancel_reason was NULL on every row in the database.
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';

const getSellerAuctions = vi.fn();
const cancelAuction = vi.fn(() => Promise.resolve({ data: {} }));
const removeAuctionUnit = vi.fn(() => Promise.resolve({ data: {} }));

vi.mock('../../api/seller', () => ({
  getSellerAuctions: (...a) => getSellerAuctions(...a),
  cancelAuction: (...a) => cancelAuction(...a),
  removeAuctionUnit: (...a) => removeAuctionUnit(...a),
  relistAuction: vi.fn(() => Promise.resolve({ data: {} })),
  rateBuyer: vi.fn(() => Promise.resolve({ data: {} })),
  featureOwnAuction: vi.fn(() => Promise.resolve({ data: {} })),
}));
vi.mock('../../api/orders', () => ({ getOrders: () => Promise.resolve({ data: [] }) }));

const MyListings = (await import('./MyListings')).default;

const auction = (over = {}) => ({
  auctionId: 1,
  title: 'GUCCI bag',
  statusName: 'ACTIVE',
  startingPrice: 2560,
  currentBid: 0,
  bidCount: 0,
  quantity: 1,
  watchCount: 0,
  startDate: '2026-07-01T00:00:00Z',
  endDate: '2030-01-01T00:00:00Z',
  ...over,
});

/** One page of the seller's twelve listings, in the shape SellerApiServlet returns. */
const page = (auctions, over = {}) => ({
  data: {
    auctions,
    total: 12,
    page: 1,
    size: 10,
    totalPages: 2,
    bucket: 'ACTIVE',
    counts: { ALL: 12, ACTIVE: 10, FINISHED: 1, UNSOLD: 1, CANCELLED: 0 },
    ...over,
  },
});

/** The params of the most recent listing request. */
const lastQuery = () => getSellerAuctions.mock.calls.at(-1)[0];

const renderPage = () => render(
  <MemoryRouter>
    <MyListings />
  </MemoryRouter>,
);

beforeEach(() => {
  getSellerAuctions.mockClear();
  cancelAuction.mockClear();
  removeAuctionUnit.mockClear();
  getSellerAuctions.mockResolvedValue(page([auction()]));
});

describe('pagination', () => {
  it('shows how many listings there are, not just the ones on screen', async () => {
    renderPage();
    expect(await screen.findByText(/Showing 1–10 of 12/)).toBeInTheDocument();
  });

  it('offers a page 2, so the 11th and 12th listings are reachable', async () => {
    renderPage();
    const next = await screen.findByRole('button', { name: /next page/i });

    getSellerAuctions.mockResolvedValue(page([auction({ auctionId: 7, title: 'Basketball' })], { page: 2 }));
    await userEvent.click(next);

    await waitFor(() => expect(lastQuery().page).toBe(2));
    expect(await screen.findByText('Basketball')).toBeInTheDocument();
  });

  it('cannot go back from the first page', async () => {
    renderPage();
    expect(await screen.findByRole('button', { name: /previous page/i })).toBeDisabled();
  });

  it('asks for one bucket at a time so the pages are honest', async () => {
    renderPage();
    await waitFor(() => expect(lastQuery().bucket).toBe('ACTIVE'));
    expect(lastQuery().size).toBe(10);
  });

  it('goes back to page 1 when the tab changes', async () => {
    // Answer with whatever page was asked for, the way the server does.
    getSellerAuctions.mockImplementation(({ page: p, bucket }) =>
      Promise.resolve(page([auction()], { page: p, bucket })));
    renderPage();
    await screen.findByText('GUCCI bag');

    await userEvent.click(screen.getByRole('button', { name: /next page/i }));
    await waitFor(() => expect(lastQuery().page).toBe(2));

    await userEvent.click(screen.getByRole('button', { name: /Cancelled/ }));

    await waitFor(() => expect(lastQuery().bucket).toBe('CANCELLED'));
    expect(lastQuery().page).toBe(1);
  });

  it('searches the whole catalogue on the server, not the loaded page', async () => {
    renderPage();
    await screen.findByText('GUCCI bag');

    await userEvent.type(screen.getByLabelText(/search your listings/i), 'gucci');

    await waitFor(() => expect(lastQuery().q).toBe('gucci'), { timeout: 2000 });
  });

  it('sorts on the server too', async () => {
    renderPage();
    await screen.findByText('GUCCI bag');

    await userEvent.selectOptions(screen.getByRole('combobox'), 'priceHigh');

    await waitFor(() => expect(lastQuery().sort).toBe('priceHigh'));
  });
});

describe('tabs tell unsold apart from cancelled', () => {
  it('offers both as separate tabs with their own counts', async () => {
    renderPage();

    expect(await screen.findByRole('button', { name: '1 Unsold' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '0 Cancelled' })).toBeInTheDocument();
  });

  it('badges an auction nobody bid on as UNSOLD, not CANCELLED', async () => {
    getSellerAuctions.mockResolvedValue(
      page([auction({ statusName: 'FINISHED', bidCount: 0 })], { bucket: 'UNSOLD' }),
    );
    renderPage();

    expect(await screen.findByText('UNSOLD')).toBeInTheDocument();
    expect(screen.queryByText('CANCELLED')).not.toBeInTheDocument();
  });

  it('badges a withdrawn listing as CANCELLED', async () => {
    getSellerAuctions.mockResolvedValue(
      page([auction({ statusName: 'CANCELLED', bidCount: 0 })], { bucket: 'CANCELLED' }),
    );
    renderPage();

    expect(await screen.findByText('CANCELLED')).toBeInTheDocument();
  });
});

describe('price shown for a listing with no bids', () => {
  it('shows the starting price rather than $0.00', async () => {
    renderPage();

    expect(await screen.findByText('$2,560.00')).toBeInTheDocument();
    expect(screen.getByText('starting price')).toBeInTheDocument();
    expect(screen.queryByText('$0.00')).not.toBeInTheDocument();
  });

  it('shows the top bid once there is one', async () => {
    getSellerAuctions.mockResolvedValue(page([auction({ bidCount: 2, currentBid: 3000.5 })]));
    renderPage();

    expect(await screen.findByText('$3,000.50')).toBeInTheDocument();
    expect(screen.queryByText('starting price')).not.toBeInTheDocument();
  });
});

describe('cancelling records a reason', () => {
  it('will not cancel until the seller says why', async () => {
    getSellerAuctions.mockResolvedValue(page([auction({ bidCount: 3, currentBid: 3000 })]));
    renderPage();
    await userEvent.click(await screen.findByRole('button', { name: /cancel this auction/i }));

    const reason = screen.getByLabelText(/reason/i);
    await userEvent.clear(reason);

    expect(screen.getByRole('button', { name: /^Cancel auction$/ })).toBeDisabled();
    expect(cancelAuction).not.toHaveBeenCalled();
  });

  it('sends the reason the seller picked', async () => {
    renderPage();
    await userEvent.click(await screen.findByRole('button', { name: /cancel this auction/i }));
    await userEvent.click(screen.getByRole('button', { name: 'Item was damaged' }));
    await userEvent.click(screen.getByRole('button', { name: /^Cancel auction$/ }));

    await waitFor(() => expect(cancelAuction).toHaveBeenCalledWith(1, 'Item was damaged'));
  });

  // Typing a reason of the seller's own used to stop after one character: the dialog's focus
  // trap re-ran on every render and pulled focus back to the first control in it.
  it('accepts a reason typed in full', async () => {
    renderPage();
    await userEvent.click(await screen.findByRole('button', { name: /cancel this auction/i }));

    const reason = screen.getByLabelText(/reason/i);
    await userEvent.clear(reason);
    await userEvent.type(reason, 'Buyer arranged a private sale');

    expect(reason).toHaveValue('Buyer arranged a private sale');
    await userEvent.click(screen.getByRole('button', { name: /^Cancel auction$/ }));

    await waitFor(() =>
      expect(cancelAuction).toHaveBeenCalledWith(1, 'Buyer arranged a private sale'));
  });

  it('warns that the bidders will be told, and how many there are', async () => {
    getSellerAuctions.mockResolvedValue(page([auction({ bidCount: 3, currentBid: 3000 })]));
    renderPage();
    await userEvent.click(await screen.findByRole('button', { name: /cancel this auction/i }));

    expect(screen.getByText(/3 bid\(s\) have been placed/)).toBeInTheDocument();
  });

  it('says plainly when there is nobody to notify', async () => {
    renderPage();
    await userEvent.click(await screen.findByRole('button', { name: /cancel this auction/i }));

    expect(screen.getByText(/no bids, so nobody needs to be notified/)).toBeInTheDocument();
  });

  it('a listing kept is a listing untouched', async () => {
    renderPage();
    await userEvent.click(await screen.findByRole('button', { name: /cancel this auction/i }));
    await userEvent.click(screen.getByRole('button', { name: /keep listing/i }));

    expect(cancelAuction).not.toHaveBeenCalled();
  });
});

describe('removing items', () => {
  it('removing the last item warns that the listing will end, and takes a reason', async () => {
    renderPage();
    await userEvent.click(await screen.findByRole('button', { name: /remove the last item/i }));

    expect(screen.getByText(/only item left/i)).toBeInTheDocument();
    expect(screen.getByText(/auction will be cancelled/i)).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: 'Item was damaged' }));
    await userEvent.click(screen.getByRole('button', { name: /remove and end listing/i }));

    await waitFor(() => expect(removeAuctionUnit).toHaveBeenCalledWith(1, 'Item was damaged'));
  });

  it('the last item cannot be removed without a reason', async () => {
    renderPage();
    await userEvent.click(await screen.findByRole('button', { name: /remove the last item/i }));

    expect(screen.getByRole('button', { name: /remove and end listing/i })).toBeDisabled();
  });

  it('removing one of several units is a plain confirm with no reason', async () => {
    getSellerAuctions.mockResolvedValue(page([auction({ quantity: 4 })]));
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(true);
    renderPage();

    await userEvent.click(await screen.findByRole('button', { name: /remove one item/i }));

    expect(confirm).toHaveBeenCalledWith(expect.stringContaining('3 will remain'));
    await waitFor(() => expect(removeAuctionUnit).toHaveBeenCalledWith(1));
    confirm.mockRestore();
  });
});
