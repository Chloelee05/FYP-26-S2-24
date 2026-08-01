import { describe, it, expect } from 'vitest';
import { apiErrorMessage } from './apiError';

describe('apiErrorMessage', () => {
  // A dead backend is the most common failure in development, and "Something went
  // wrong" would send the user looking in the wrong place.
  it('names the unreachable server when there is no response', () => {
    expect(apiErrorMessage(new Error('Network Error'))).toMatch(/Cannot reach the server/);
    expect(apiErrorMessage(undefined)).toMatch(/Cannot reach the server/);
  });

  it('prefers the server-supplied error field', () => {
    const err = { response: { data: { error: 'Bid too low.' } } };
    expect(apiErrorMessage(err, 'Fallback.')).toBe('Bid too low.');
  });

  // Some servlets report failures as `message` instead of `error`.
  it('falls back to the message field', () => {
    const err = { response: { data: { message: 'Password incorrect.' } } };
    expect(apiErrorMessage(err, 'Fallback.')).toBe('Password incorrect.');
  });

  it('prefers error over message when both are present', () => {
    const err = { response: { data: { error: 'From error', message: 'From message' } } };
    expect(apiErrorMessage(err)).toBe('From error');
  });

  it('uses the caller fallback when the response carries neither', () => {
    expect(apiErrorMessage({ response: { data: {} } }, 'Could not save.')).toBe('Could not save.');
    expect(apiErrorMessage({ response: { data: null } }, 'Could not save.')).toBe('Could not save.');
    expect(apiErrorMessage({ response: {} }, 'Could not save.')).toBe('Could not save.');
  });

  it('uses a generic default when no fallback is given', () => {
    expect(apiErrorMessage({ response: { data: {} } })).toBe('Something went wrong.');
  });
});
