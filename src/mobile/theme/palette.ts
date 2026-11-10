/**
 * The palette for the futuristic crimson interface, in one place.
 *
 * Everything visual — the CSS variables, the 3D suit materials and the light rig — reads from
 * here, so the UI and the character can never drift into different themes again.
 */
export const CRIMSON = {
  /** Deep near-black background with a red undertone. */
  bg: 0x0a0406,
  panel: 0x160a0e,
  /** Crimson primary, used for edges, plates and the suits' accent panels. */
  crimson: 0xd10d2b,
  crimsonBright: 0xff2442,
  /** Hot metal glow: the arc core, eyes and seams. */
  ember: 0xff5533,
  gold: 0xf0b45c,
  steel: 0x6e7480,
  steelDark: 0x22262e,
  /** Cold blue only as a tiny contrast so the red reads as red, never as orange. */
  ice: 0x8fd8ff,
} as const;

/** Hex strings for CSS, for a single source of truth with the 3D materials. */
export const CRIMSON_CSS = {
  accent: '#ff2442',
  accentSoft: '#ff244226',
  ember: '#ff7a3c',
  gold: '#f0b45c',
} as const;
