import { Injectable } from '@angular/core';

// the longest side of a stored photo
const MAX_PHOTO_SIZE = 2048;

const JPEG_QUALITY = 0.85;

// photos are stored scaled down and re-encoded: drawn upright as the camera took them, without
// their exif data, which can tell where they were taken
@Injectable({ providedIn: 'root' })
export class PhotoScaler {
  async scale(photo: Blob): Promise<Blob> {
    const bitmap = await createImageBitmap(photo, { imageOrientation: 'from-image' });
    try {
      const factor = Math.min(1, MAX_PHOTO_SIZE / Math.max(bitmap.width, bitmap.height));
      const canvas = document.createElement('canvas');
      canvas.width = Math.round(bitmap.width * factor);
      canvas.height = Math.round(bitmap.height * factor);
      canvas.getContext('2d')!.drawImage(bitmap, 0, 0, canvas.width, canvas.height);
      return await new Promise<Blob>((resolve, reject) =>
        canvas.toBlob(
          (blob) => (blob ? resolve(blob) : reject(new Error('the photo could not be encoded'))),
          'image/jpeg',
          JPEG_QUALITY,
        ),
      );
    } finally {
      bitmap.close();
    }
  }
}
