/** Motion constants (seconds). One orchestrated reveal on load: each design's line runs from the moment the
 * failure was noticed to the moment it was fixed. Everything else changes only when the reader acts. */
export const motion = {
  base: 0.7,
  stagger: 0.07,
  quick: 0.2,
  ease: [0.2, 0.8, 0.25, 1] as const,
};
