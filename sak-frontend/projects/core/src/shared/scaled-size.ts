// the sizes the backend scales a photo to, on its longer side (FileService.SCALED_SIZES)
export const SCALED_SIZES = [128, 256, 480, 1024] as const;

// the size to ask for of a photo that shows at cssPixels on the screen: the smallest scaled size
// that has at least as many pixels as the screen shows there, at its pixel ratio; null when no
// scaled size is that large, the photo itself then
export function scaledSize(
  cssPixels: number,
  ratio: number = globalThis.devicePixelRatio || 1,
): number | null {
  const needed = Math.ceil(cssPixels * ratio);
  return SCALED_SIZES.find((size) => size >= needed) ?? null;
}
