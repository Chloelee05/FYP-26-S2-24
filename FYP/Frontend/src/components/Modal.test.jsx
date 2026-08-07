import { useState } from 'react';
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
    open({ onClose });
    fireEvent.mouseDown(document.querySelector('.modal-backdrop'));
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  // Destructive and multi-step dialogs opt out, so a stray click cannot discard work.
  it('ignores the backdrop when dismissOnBackdrop is false', () => {
    const onClose = vi.fn();
    open({ onClose, dismissOnBackdrop: false });
    fireEvent.mouseDown(document.querySelector('.modal-backdrop'));
    expect(onClose).not.toHaveBeenCalled();
  });

  // `position: fixed` resolves against the nearest ancestor with a backdrop-filter,
  // filter or transform rather than the viewport, which clipped dialogs opened from
  // the blurred navbar. Portalling to the body keeps the overlay on the viewport.
  it('portals onto the body so a filtered ancestor cannot capture the overlay', () => {
    const { container } = render(
      <nav className="backdrop-blur-lg">
        <Modal title="Confirm" onClose={vi.fn()}>
          <button type="button">Yes</button>
        </Modal>
      </nav>,
    );
    expect(container.querySelector('[role="dialog"]')).toBeNull();
    expect(screen.getByRole('dialog').closest('.modal-backdrop').parentElement).toBe(document.body);
  });

  it('scrolls the body inside the panel so the header stays put', () => {
    open();
    const body = screen.getByRole('button', { name: 'Yes' }).parentElement;
    expect(body).toHaveClass('overflow-y-auto');
    expect(body.parentElement).toHaveClass('modal-panel');
  });

  // Dialogs that lay out their own column (a chat pane, a pinned footer) need their
  // children to stay direct flex items of the panel.
  it('leaves children directly on the panel when scrollBody is false', () => {
    open({ scrollBody: false });
    expect(screen.getByRole('button', { name: 'Yes' }).parentElement).toHaveClass('modal-panel');
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

  // Callers pass an inline arrow for onClose, so it changes identity every render. When
  // that fed the setup effect's dependency list, each keystroke in a controlled field
  // tore the effect down and re-ran it, throwing focus back to the close button — you
  // had to click the field again after every character.
  it('keeps focus in a controlled field while typing', () => {
    function Host() {
      const [value, setValue] = useState('');
      return (
        <Modal title="Edit Category" onClose={() => {}}>
          <input aria-label="Name" value={value} onChange={e => setValue(e.target.value)} />
        </Modal>
      );
    }
    render(<Host />);

    const input = screen.getByLabelText('Name');
    input.focus();
    fireEvent.change(input, { target: { value: 'M' } });
    expect(input).toHaveFocus();

    fireEvent.change(input, { target: { value: "Men's & Co" } });
    expect(input).toHaveFocus();
    expect(input).toHaveValue("Men's & Co");
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
