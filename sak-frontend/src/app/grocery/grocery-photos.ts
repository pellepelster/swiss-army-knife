import { HttpClient } from '@angular/common/http';
import { inject, Injectable, Pipe, PipeTransform } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { scaledSize } from '../shared/scaled-size';
import { GroceryItem } from './grocery.service';

// how large the photo of an item shows in its row, in css pixels
export const PHOTO_IN_ROW = 48;

// the photos of the items, served only with the session, which an img can not send: fetched with it
// and shown as object urls. A photo is fetched in the size it shows at on the screen
// (scaledSize), each file and size once; the file id names the photo, it never changes
@Injectable({ providedIn: 'root' })
export class GroceryPhotos {
  private readonly http = inject(HttpClient);
  private readonly scaled = new Map<string, Promise<string>>();

  // the photo in the row of the item
  inRow(item: GroceryItem): Promise<string> {
    const size = scaledSize(PHOTO_IN_ROW);
    const key = `${item.photoId}-${size}`;
    let url = this.scaled.get(key);
    if (!url) {
      url = this.objectUrl(item, size);
      // a failed one is asked for again next time
      url.catch(() => this.scaled.delete(key));
      this.scaled.set(key, url);
    }
    return url;
  }

  // the photo as large as the screen shows it; the caller revokes the url once it is closed
  full(item: GroceryItem): Promise<string> {
    const screen = Math.max(globalThis.innerWidth || 0, globalThis.innerHeight || 0);
    return this.objectUrl(item, scaledSize(screen));
  }

  private async objectUrl(item: GroceryItem, size: number | null): Promise<string> {
    const url = `/api/v1/grocery/items/${item.id}/photos/${item.photoId}`;
    const blob = await firstValueFrom(
      this.http.get(url, { responseType: 'blob', params: size === null ? {} : { size } }),
    );
    return URL.createObjectURL(blob);
  }
}

// the object url of the photo of an item in its row, for [src]; null until it is fetched
@Pipe({ name: 'photoInRow' })
export class PhotoInRowPipe implements PipeTransform {
  private readonly photos = inject(GroceryPhotos);

  transform(item: GroceryItem): Promise<string> {
    return this.photos.inRow(item);
  }
}
