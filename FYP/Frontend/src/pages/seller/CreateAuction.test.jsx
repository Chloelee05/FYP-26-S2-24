// The minimum requirements name "products, services, customers, auction transactions", but the
// create form had no way to say which of the first two a listing was: every new listing was a
// PRODUCT and only an admin could reclassify it afterwards, which made services an admin
// correction rather than something a seller could offer. These cover the seller's end of that.
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';

const createAuction = vi.fn(() => Promise.resolve({ data: { auctionId: 99 } }));
const navigate = vi.fn();

vi.mock('../../api/seller', () => ({
  createAuction: (...a) => createAuction(...a),
}));
vi.mock('../../api/auction', () => ({
  getCategories: () => Promise.resolve({ data: [{ id: 1, name: 'Lessons' }] }),
  getTags: () => Promise.resolve({ data: {} }),
}));
vi.mock('react-router-dom', async () => ({
  ...(await vi.importActual('react-router-dom')),
  useNavigate: () => navigate,
}));
// The real uploader talks to the upload endpoint. The form only needs at least one photo to
// pass its own guard, so stand in a control that reports one.
vi.mock('../../components/ImageUploader', () => ({
  default: ({ onChange }) => (
    <button type="button" onClick={() => onChange(['photo.jpg'])}>add photo</button>
  ),
}));

const CreateAuction = (await import('./CreateAuction')).default;

const renderPage = () => render(
  <MemoryRouter>
    <CreateAuction />
  </MemoryRouter>,
);

/** Fills everything the form requires except the product/service kind. */
async function fillRequiredFields() {
  await userEvent.type(screen.getByPlaceholderText('Item title'), '10 Session Guitar Lessons');
  await userEvent.type(screen.getByPlaceholderText(/Describe your item/), 'Ten one-hour lessons.');
  // The category label carries no `for`, so the select is reached through the option the
  // mocked category endpoint supplies.
  const category = (await screen.findByRole('option', { name: 'Lessons' })).closest('select');
  await userEvent.selectOptions(category, 'Lessons');
  await userEvent.type(screen.getByPlaceholderText('0.00'), '100');
  // Start Time and End Time are the two datetime-local inputs, in that order; neither label
  // carries a `for`.
  const [, endTime] = document.querySelectorAll('input[type="datetime-local"]');
  fireEvent.change(endTime, { target: { value: '2030-01-31T10:00' } });
  await userEvent.click(screen.getByRole('button', { name: 'add photo' }));
}

const submitted = () => createAuction.mock.calls.at(-1)[0];

beforeEach(() => {
  createAuction.mockClear();
  navigate.mockClear();
});

describe('choosing a product or a service', () => {
  it('offers both, which the form did not before', async () => {
    renderPage();

    const kind = await screen.findByLabelText(/This listing is a/);
    expect(kind).toBeInTheDocument();
    expect(screen.getByRole('option', { name: 'Product' })).toBeInTheDocument();
    expect(screen.getByRole('option', { name: 'Service' })).toBeInTheDocument();
  });

  // Existing behaviour has to be unchanged: every listing made before this field existed is a
  // physical good, and the column defaults to PRODUCT.
  it('defaults to a product', async () => {
    renderPage();

    expect(await screen.findByLabelText(/This listing is a/)).toHaveValue('PRODUCT');
  });

  it('sends SERVICE when the seller picks one', async () => {
    renderPage();
    await fillRequiredFields();
    await userEvent.selectOptions(screen.getByLabelText(/This listing is a/), 'SERVICE');
    await userEvent.click(screen.getByRole('button', { name: /Create Auction/ }));

    await waitFor(() => expect(createAuction).toHaveBeenCalled());
    expect(submitted().listingKind).toBe('SERVICE');
  });

  it('sends PRODUCT when the seller leaves it alone', async () => {
    renderPage();
    await fillRequiredFields();
    await userEvent.click(screen.getByRole('button', { name: /Create Auction/ }));

    await waitFor(() => expect(createAuction).toHaveBeenCalled());
    expect(submitted().listingKind).toBe('PRODUCT');
  });

  it('explains what a service is, so the choice is not a guess', async () => {
    renderPage();
    await userEvent.selectOptions(
      await screen.findByLabelText(/This listing is a/), 'SERVICE');

    expect(screen.getByText(/Nothing is shipped/i)).toBeInTheDocument();
  });
});

describe('the preview matches what a buyer will be shown', () => {
  it('badges a service and warns that nothing will be shipped', async () => {
    renderPage();
    await userEvent.selectOptions(
      await screen.findByLabelText(/This listing is a/), 'SERVICE');
    await userEvent.click(screen.getByRole('button', { name: /^Preview$/ }));

    // Scoped to the dialog: "Service" is also the text of an <option> in the form behind it.
    const preview = within(screen.getByRole('dialog'));
    expect(preview.getByText('Service')).toBeInTheDocument();
    expect(preview.getByText(/nothing will be shipped/i)).toBeInTheDocument();
  });

  // "Brand New" said about ten guitar lessons is not information a buyer can use, so neither
  // the preview nor the public auction page claims a condition for a service.
  it('does not claim a condition for a service', async () => {
    renderPage();
    await userEvent.selectOptions(
      await screen.findByLabelText(/This listing is a/), 'SERVICE');
    await userEvent.click(screen.getByRole('button', { name: /^Preview$/ }));

    const preview = within(screen.getByRole('dialog'));
    expect(preview.queryByText(/Condition: Brand New/)).not.toBeInTheDocument();
  });

  it('still shows the condition for a product', async () => {
    renderPage();
    await userEvent.click(await screen.findByRole('button', { name: /^Preview$/ }));

    const preview = within(screen.getByRole('dialog'));
    expect(preview.getByText('Product')).toBeInTheDocument();
    expect(preview.getByText(/Condition: Brand New/)).toBeInTheDocument();
  });
});
