import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { firstValueFrom, take, toArray } from 'rxjs';
import { signedIn } from '../testing/page';
import { GroceryChanges } from './grocery-changes';

// an answer of the backend whose body sends the chunks and stays open
function streaming(chunks: string[], status = 200): Response {
  const encoder = new TextEncoder();
  const body = new ReadableStream<Uint8Array>({
    start(controller) {
      chunks.forEach((chunk) => controller.enqueue(encoder.encode(chunk)));
    },
  });
  return new Response(body, { status, headers: { 'Content-Type': 'text/event-stream' } });
}

describe('GroceryChanges', () => {
  beforeEach(() => {
    // listens only while the tab is visible, whatever a spec before left
    Object.defineProperty(document, 'visibilityState', { value: 'visible', configurable: true });
    localStorage.clear();
    signedIn();
    TestBed.configureTestingModule({ providers: [provideRouter([])] });
  });

  afterEach(() => {
    Reflect.deleteProperty(document, 'visibilityState');
    vi.unstubAllGlobals();
  });

  it('hears the ids of the lists that changed, with the session in the header, not in the url', async () => {
    const fetch = vi.fn().mockResolvedValue(
      streaming([
        ': listening\n\n',
        // an event may come in pieces
        'event: changed\ndata: {"listId"',
        ':2}\n\n: keep-alive\n\nevent: changed\ndata: {"listId":7}\n\n',
      ]),
    );
    vi.stubGlobal('fetch', fetch);

    const ids = await firstValueFrom(
      TestBed.inject(GroceryChanges).changes().pipe(take(2), toArray()),
    );

    expect(ids).toEqual([2, 7]);
    const [url, init] = fetch.mock.calls[0] as [string, RequestInit];
    expect(url).toBe('/api/v1/grocery/changes');
    expect((init.headers as Record<string, string>)['Authorization']).toBe('Bearer session-token');
  });
});
