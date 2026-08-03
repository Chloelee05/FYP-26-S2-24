// The payment-method and profile endpoints all POST url-encoded forms to one path and are
// told apart by an `action` field, so what gets encoded is the whole contract. These pin the
// new update action and, just as importantly, the encoding rule the profile fix depends on:
// an empty string must reach the server as an empty parameter (the member cleared the field),
// while null/undefined must be left out entirely (the form did not carry it), because the
// server treats those two cases differently.
import { describe, it, expect, vi, beforeEach } from 'vitest';

const get = vi.fn(() => Promise.resolve({ data: {} }));
const post = vi.fn(() => Promise.resolve({ data: {} }));
vi.mock('./config.js', () => ({ default: { get, post } }));

const user = await import('./user.js');

beforeEach(() => { get.mockClear(); post.mockClear(); });

/** The submitted form of the most recent POST, as a plain object. */
const submitted = () => Object.fromEntries(post.mock.calls[0][1].entries());

const submittedKeys = () => [...post.mock.calls[0][1].keys()];

describe('updatePaymentMethod', () => {
  it('posts action=update with the id and the editable card fields', () => {
    user.updatePaymentMethod(5, { cardHolder: 'Alice B Tan', expMonth: '9', expYear: '2031' });

    expect(post.mock.calls[0][0]).toBe('/account/payment-methods');
    expect(submitted()).toEqual({
      action: 'update',
      id: '5',
      cardHolder: 'Alice B Tan',
      expMonth: '9',
      expYear: '2031',
    });
  });

  it('carries only the PayPal email for a PayPal method', () => {
    user.updatePaymentMethod(6, { paypalEmail: 'alice.new@paypal.com' });
    expect(submitted()).toEqual({
      action: 'update', id: '6', paypalEmail: 'alice.new@paypal.com',
    });
  });

  it('carries holder and bank name for a bank method, never the account number', () => {
    user.updatePaymentMethod(7, { accountHolder: 'Alice B Tan', bankName: 'OCBC' });
    expect(submittedKeys()).not.toContain('accountNumber');
    expect(submitted()).toEqual({
      action: 'update', id: '7', accountHolder: 'Alice B Tan', bankName: 'OCBC',
    });
  });

  it('sends the url-encoded content type the servlet reads parameters from', () => {
    user.updatePaymentMethod(5, { cardHolder: 'Alice' });
    expect(post.mock.calls[0][2].headers['Content-Type'])
      .toBe('application/x-www-form-urlencoded');
  });
});

describe('the other payment-method actions still send their own action', () => {
  it('add', () => {
    user.addPaymentMethod({ cardHolder: 'Alice', cardNumber: '4111111111111111' });
    expect(submitted().action).toBe('add');
  });

  it('delete', () => {
    user.deletePaymentMethod(5);
    expect(submitted()).toEqual({ action: 'delete', id: '5' });
  });

  it('default', () => {
    user.setDefaultPaymentMethod(5);
    expect(submitted()).toEqual({ action: 'default', id: '5' });
  });
});

describe('updateProfile encoding', () => {
  it('sends an emptied field as an empty parameter, so the server clears it', () => {
    user.updateProfile({ username: 'alice', phone: '', address: '1 Orchard Rd' });
    expect(submittedKeys()).toContain('phone');
    expect(submitted().phone).toBe('');
  });

  it('omits a field that is null, so the server keeps what it has', () => {
    user.updateProfile({ username: 'alice', phone: null });
    expect(submittedKeys()).not.toContain('phone');
  });

  it('omits a field that is undefined for the same reason', () => {
    user.updateProfile({ username: 'alice', address: undefined });
    expect(submittedKeys()).not.toContain('address');
  });

  it('never sends the email — it is the sign-in identity and is read-only', () => {
    user.updateProfile({ username: 'alice', phone: '+6591234567', address: '1 Orchard Rd' });
    expect(submittedKeys()).not.toContain('email');
  });
});
