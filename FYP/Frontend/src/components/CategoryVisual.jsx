import { categoryLook } from '../utils/categoryLook';
import { publicPath } from '../utils/appBase';

const SIZES = {
  sm: { box: 'w-10 h-10 rounded-xl', icon: 18 },
  md: { box: 'w-14 h-14 rounded-2xl', icon: 22 },
  lg: { box: 'w-20 h-20 rounded-2xl', icon: 30 },
};

/**
 * The square visual for one category.
 *
 * Categories are admin-managed, so the picture resolves in three steps: the uploaded
 * image, then the built-in icon matched to the name (see {@link categoryLook}), then a
 * generic tag. A brand-new category therefore looks deliberate straight away, and an
 * upload is only ever an override.
 *
 * @param category  a category row; only `name` and `imageUrl` are read
 * @param size      'sm' (admin table) | 'md' (home tiles) | 'lg' (edit dialog preview)
 */
export default function CategoryVisual({ category, size = 'md', className = '' }) {
  const { box, icon } = SIZES[size] ?? SIZES.md;
  const imageUrl = category?.imageUrl;

  if (imageUrl) {
    return (
      <img
        src={publicPath(imageUrl)}
        alt=""
        loading="lazy"
        className={`${box} object-cover shrink-0 bg-ink-100 ${className}`}
      />
    );
  }

  const { Icon, tint } = categoryLook(category?.name ?? '');
  return (
    <span className={`grid place-items-center shrink-0 ${box} ${tint} ${className}`}>
      <Icon size={icon} strokeWidth={1.75} />
    </span>
  );
}
