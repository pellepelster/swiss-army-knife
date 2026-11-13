import { HttpClient } from '@angular/common/http';
import { inject, Injectable, Pipe, PipeTransform } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { scaledSize } from '../shared/scaled-size';
import { GroceryItem } from './grocery.service';

// in css pixels
export const PHOTO_IN_ROW = 48;

// the photos need the session, which an img can not send: fetched with it and shown as object
// urls, in the size shown on the screen, each file and size once (a file id never changes)
@Injectable({ providedIn: 'root' })
export class GroceryPhotos {
  private readonly http = inject(HttpClient);
  private readonly scaled = new Map<string, Promise<string>>();

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

  // the caller revokes the url once it is closed
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

// null until it is fetched, and when it can not be, e.g. another user took it away meanwhile
@Pipe({ name: 'photoInRow' })
export class PhotoInRowPipe implements PipeTransform {
  private readonly photos = inject(GroceryPhotos);

  transform(item: GroceryItem): Promise<string | null> {
    return this.photos.inRow(item).catch(() => null);
  }
}
