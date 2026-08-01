import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import Modal from './Modal';

const open = (props = {}) =>
  render(
    <Modal title="Confirm" onClose={props.onClose ?? vi.fn()} {...props}>
      <button type="button">Yes</button>
      <button type="button">No</button>
    </Modal>,
  );

describe('Modal', () => {
  it('labels the dialog with its title', () => {
    open();
    expect(screen.getByRole('dialog')).toHaveAccessibleName('Confirm');
  });

  it('falls back to a generic name when there is no title', () => {
    render(<Modal onClose={vi.fn()}><span>body</span></Modal>);
    expect(screen.getByRole('dialog')).toHaveAccessibleName('Dialog');
  });

  it('closes on Escape', () => {
    const onClose = vi.fn();
    open({ onClose });
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('closes on the close button', () => {
    const onClose = vi.fn();
    open({ onClose });
    fireEvent.click(screen.getByRole('button', { name: 'Close dialog' }));
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('closes on a backdrop press by default', () => {
    const onClose = vi.fn();
    const { container } = open({ onClose });
    fireEvent.mouseDown(container.querySelector('.modal-backdrop'));
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  // Destructive and multi-step dialogs opt out, so a stray click cannot discard work.
  it('ignores the backdrop when dismissOnBackdrop is false', () => {
    const onClose = vi.fn();
    const { container } = open({ onClose, dismissOnBackdrop: false });
    fireEvent.mouseDown(container.querySelector('.modal-backdrop'));
    expect(onClose).not.toHaveBeenCalled();
  });

  it('ignores a press that starts inside the panel', () => {
    const onClose = vi.fn();
    open({ onClose });
    fireEvent.mouseDown(screen.getByRole('dialog'));
    expect(onClose).not.toHaveBeenCalled();
  });

  it('moves focus to the first focusable element on open', () => {
    open();
    expect(screen.getByRole('button', { name: 'Close dialog' })).toHaveFocus();
  });

  it('restores focus to whatever opened it', () => {
    const opener = document.createElement('button');
    document.body.appendChild(opener);
    opener.focus();

    const { unmount } = open();
    expect(opener).not.toHaveFocus();

    unmount();
    expect(opener).toHaveFocus();
    opener.remove();
  });

  it('locks page scroll while open and restores it on close', () => {
    expect(document.body.style.overflow).toBe('');
    const { unmount } = open();
    expect(document.body.style.overflow).toBe('hidden');
    unmount();
    expect(document.body.style.overflow).toBe('');
  });
});
