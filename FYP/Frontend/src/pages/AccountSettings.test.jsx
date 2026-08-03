// The payment-methods tab gained the "update" half of maintaining account details, and the
// delete-account tab's copy was describing something the server does not do. These cover both:
// that a saved method can genuinely be edited from the UI (and that the fields offered match
// the method's type), and that the closure warning now describes the real anonymisation.
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { AuthContext } from '../context/AuthContext';

const getPaymentMethods = vi.fn();
const updatePaymentMethod = vi.fn(() => Promise.resolve({ data: {} }));
const deletePaymentMethod = vi.fn(() => Promise.resolve({ data: {} }));

vi.mock('../api/user', () => ({
  getProfile: () => Promise.resolve({ data: { username: 'alice', email: 'a@b.com' } }),
  updateProfile: vi.fn(() => Promise.resolve({ data: {} })),
  uploadProfilePhoto: vi.fn(),
  deleteAccount: vi.fn(() => Promise.resolve({ data: {} })),
  getPaymentMethods: (...a) => getPaymentMethods(...a),
  addPaymentMethod: vi.fn(() => Promise.resolve({ data: {} })),
  updatePaymentMethod: (...a) => updatePaymentMethod(...a),
  deletePaymentMethod: (...a) => deletePaymentMethod(...a),
  setDefaultPaymentMethod: vi.fn(() => Promise.resolve({ data: {} })),
}));
vi.mock('../api/auth', () => ({ changePassword: vi.fn() }));
vi.mock('../api/twoFactor', () => ({ setup2FA: vi.fn(), confirm2FA: vi.fn(), disable2FA: vi.fn() }));
vi.mock('../api/notifications', () => ({
  getNotificationPreferences: () => Promise.resolve({ data: {} }),
  saveNotificationPreferences: vi.fn(),
  saveTelegramPreferences: vi.fn(),
}));
vi.mock('../api/telegram', () => ({
  getTelegramStatus: () => Promise.resolve({ data: { available: false } }),
  unlinkTelegram: vi.fn(),
}));

const AccountSettings = (await import('./AccountSettings')).default;

const CARD = {
  id: 5, methodType: 'CARD', cardHolder: 'Alice Tan', cardBrand: 'Visa', last4: '4242',
  expMonth: 12, expYear: 2030, default: true, displayLabel: 'Visa ****4242',
};
const PAYPAL = {
  id: 6, methodType: 'PAYPAL', cardBrand: 'PayPal', accountRef: 'alice@paypal.com',
  default: false, displayLabel: 'PayPal (alice@paypal.com)',
};
const BANK = {
  id: 7, methodType: 'BANK_TRANSFER', cardHolder: 'Alice Tan', last4: '6789',
  accountRef: 'DBS', default: false, displayLabel: 'DBS account ****6789',
};

function renderSettings(tab) {
  return render(
    <AuthContext.Provider value={{ user: { username: 'alice' }, setUser: vi.fn(), logout: vi.fn() }}>
      <MemoryRouter initialEntries={[`/settings?tab=${tab}`]}>
        <AccountSettings />
      </MemoryRouter>
    </AuthContext.Provider>,
  );
}

/** Opens the editor for the method whose label matches, and returns that form only. */
async function openEditor(labelPattern) {
  await userEvent.click(await screen.findByRole('button', { name: labelPattern }));
  return within(screen.getByRole('form', { name: labelPattern }));
}

beforeEach(() => {
  updatePaymentMethod.mockClear();
  deletePaymentMethod.mockClear();
  getPaymentMethods.mockReset();
  getPaymentMethods.mockResolvedValue({ data: [CARD] });
});

describe('editing a saved payment method', () => {
  it('offers an edit control on each saved method', async () => {
    renderSettings('payment');
    expect(await screen.findByRole('button', { name: /Edit Visa \*\*\*\*4242/ })).toBeInTheDocument();
  });

  it('opens a card editor pre-filled with the stored holder and expiry', async () => {
    renderSettings('payment');
    const editor = await openEditor(/Edit Visa/);

    expect(editor.getByLabelText('Cardholder name')).toHaveValue('Alice Tan');
    expect(editor.getByLabelText('Exp. month')).toHaveValue(12);
    expect(editor.getByLabelText('Exp. year')).toHaveValue(2030);
  });

  it('does not offer the card number for editing, and says why', async () => {
    renderSettings('payment');
    const editor = await openEditor(/Edit Visa/);

    expect(editor.queryByLabelText('Card number')).not.toBeInTheDocument();
    expect(editor.getByText(/card number cannot be changed/i)).toBeInTheDocument();
  });

  it('submits the edited values and reloads the list', async () => {
    renderSettings('payment');
    const editor = await openEditor(/Edit Visa/);

    const holder = editor.getByLabelText('Cardholder name');
    await userEvent.clear(holder);
    await userEvent.type(holder, 'Alice B Tan');
    await userEvent.click(editor.getByRole('button', { name: 'Save changes' }));

    await waitFor(() => expect(updatePaymentMethod).toHaveBeenCalledWith(5, {
      cardHolder: 'Alice B Tan', expMonth: '12', expYear: '2030',
    }));
    // Two loads: the initial one and the refresh after saving.
    await waitFor(() => expect(getPaymentMethods).toHaveBeenCalledTimes(2));
  });

  it('closes the editor without saving when cancelled', async () => {
    renderSettings('payment');
    const editor = await openEditor(/Edit Visa/);
    await userEvent.click(editor.getByRole('button', { name: 'Cancel' }));

    expect(updatePaymentMethod).not.toHaveBeenCalled();
    expect(screen.queryByRole('form', { name: /Edit Visa/ })).not.toBeInTheDocument();
  });

  it('offers only the email for a PayPal method', async () => {
    getPaymentMethods.mockResolvedValue({ data: [PAYPAL] });
    renderSettings('payment');
    const editor = await openEditor(/Edit PayPal/);

    expect(editor.getByLabelText('PayPal email')).toHaveValue('alice@paypal.com');
    expect(editor.queryByLabelText('Exp. month')).not.toBeInTheDocument();
  });

  it('offers holder and bank name for a bank method, never the account number', async () => {
    getPaymentMethods.mockResolvedValue({ data: [BANK] });
    renderSettings('payment');
    const editor = await openEditor(/Edit DBS/);

    expect(editor.getByLabelText('Account holder name')).toHaveValue('Alice Tan');
    expect(editor.getByLabelText('Bank name')).toHaveValue('DBS');
    expect(editor.queryByLabelText('Account number')).not.toBeInTheDocument();
  });
});

describe('delete-account copy', () => {
  it('no longer claims bids and the watchlist are removed — they are kept by design', () => {
    renderSettings('danger');
    expect(screen.queryByText(/listings, bids and watchlist are removed/i)).not.toBeInTheDocument();
  });

  it('says personal details are erased and past bids are kept unattributed', () => {
    renderSettings('danger');
    expect(screen.getByText(/personal details are erased/i)).toBeInTheDocument();
    expect(screen.getByText(/past bids and reviews stay/i)).toBeInTheDocument();
  });

  it('warns that running listings are cancelled and paid buyers are refunded', () => {
    renderSettings('danger');
    expect(screen.getByText(/still running is cancelled/i)).toBeInTheDocument();
    expect(screen.getByText(/refund is raised for them automatically/i)).toBeInTheDocument();
  });
});
