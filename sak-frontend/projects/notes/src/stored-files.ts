import { HttpClient } from '@angular/common/http';
import { inject, Injectable, Pipe, PipeTransform } from '@angular/core';
import { firstValueFrom } from 'rxjs';

// the files of the backend's file storage are only served with the session, which an img, a link
// or an audio element can not send; they are fetched with it and shown as object urls instead
@Injectable({ providedIn: 'root' })
export class StoredFiles {
  private readonly http = inject(HttpClient);

  // the thumbnails, each fetched once; they are small and shown again and again
  private readonly thumbnails = new Map<number, Promise<string>>();

  static url(id: number): string {
    return `/api/v1/files/${id}`;
  }

  thumbnail(id: number): Promise<string> {
    let url = this.thumbnails.get(id);
    if (!url) {
      url = this.objectUrl(`${StoredFiles.url(id)}/thumbnail`);
      // a failed one is asked for again next time
      url.catch(() => this.thumbnails.delete(id));
      this.thumbnails.set(id, url);
    }
    return url;
  }

  // the whole file, the caller revokes the url once it is done with it
  file(id: number): Promise<string> {
    return this.objectUrl(StoredFiles.url(id));
  }

  private async objectUrl(url: string): Promise<string> {
    return URL.createObjectURL(await firstValueFrom(this.http.get(url, { responseType: 'blob' })));
  }
}

// the object url of the thumbnail of a stored file, for [src]; null until it is fetched
@Pipe({ name: 'thumbnail' })
export class ThumbnailPipe implements PipeTransform {
  private readonly files = inject(StoredFiles);

  transform(id: number): Promise<string> {
    return this.files.thumbnail(id);
  }
}
