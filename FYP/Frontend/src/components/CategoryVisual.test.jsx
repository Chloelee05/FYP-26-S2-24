import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import CategoryVisual from './CategoryVisual';
import { categoryLook } from '../utils/categoryLook';

const iconFor = (name) => categoryLook(name).Icon;

describe('categoryLook', () => {
  it('matches a category to its own icon', () => {
    const names = ['Electronics', 'Cars', 'Art', 'Sports', 'Fine Jewelry', 'Property'];
    const icons = names.map(iconFor);
    expect(new Set(icons).size).toBe(names.length);
  });

  it('matches regardless of case, plural or surrounding words', () => {
    expect(iconFor("Men's Fashion")).toBe(iconFor('WOMENS FASHION'));
    expect(iconFor('Mobile Phones & Gadgets')).toBe(iconFor('phone'));
    expect(iconFor('collectibles')).not.toBe(iconFor(''));
  });

  // A name carrying two concepts ("Toys & Collectibles") takes whichever keyword sits
  // higher in the table; both icons suit it, so the rule just has to be predictable.
  it('resolves a name with two keywords to the earlier one', () => {
    expect(iconFor('Toys & Collectibles')).toBe(iconFor('Toys'));
  });

  // Keywords match at a word start, not anywhere: a plain substring test puts a shirt on
  // "Equipment" (men) and a palette on "Smart Home" (art).
  it('does not match a keyword buried inside another word', () => {
    const fallback = iconFor('');
    expect(iconFor('Equipment')).toBe(fallback);
    expect(iconFor('Smart Home')).not.toBe(iconFor('Art'));
  });

  // "Furniture & Home Living" has to reach `furniture` before the broader `home`.
  it('prefers the more specific keyword when a name matches two', () => {
    expect(iconFor('Furniture & Home Living')).toBe(iconFor('Furniture'));
    expect(iconFor('Furniture & Home Living')).not.toBe(iconFor('Home & Garden'));
  });

  it('falls back for a name it does not recognise', () => {
    expect(iconFor('plsy')).toBe(iconFor('zzzz'));
    expect(iconFor(null)).toBe(iconFor(''));
  });

  it('survives a name with regex metacharacters', () => {
    expect(() => iconFor('C++ (*) [books]')).not.toThrow();
    expect(iconFor('C++ (*) [books]')).toBe(iconFor('Books'));
  });
});

describe('CategoryVisual', () => {
  it('shows the uploaded picture when there is one', () => {
    render(<CategoryVisual category={{ name: 'Cars', imageUrl: '/uploads/category/a.png' }} />);
    expect(screen.getByRole('presentation', { hidden: true }).getAttribute('src')).toContain(
      '/uploads/category/a.png',
    );
  });

  it('falls back to the matched icon when there is no picture', () => {
    const { container } = render(<CategoryVisual category={{ name: 'Cars' }} />);
    expect(container.querySelector('img')).toBeNull();
    expect(container.querySelector('svg')).not.toBeNull();
  });
});
