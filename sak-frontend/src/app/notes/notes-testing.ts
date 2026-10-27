// what the backend answers in the tests of the notes
const CREATED = '2026-10-27T08:00:00Z';

export const noteResponse = (
  id: number,
  text: string,
  color: string,
  labels: string[],
  photos: string[] = [],
) => ({
  id,
  text,
  color,
  labels: labels.map((name, index) => ({ id: index + 1, name })),
  photos: photos.map((sha256) => ({ sha256, url: `/files/${sha256}` })),
  createdAt: CREATED,
  updatedAt: CREATED,
});

// what a list endpoint answers with the items
export const pageOf = <T>(items: T[]) => ({
  items,
  pagination: { offset: 0, limit: 500, total: items.length },
});

// the photo as the browser would scale it, scaling needs a real canvas
export const SCALED = new Blob(['scaled'], { type: 'image/jpeg' });
export const scaler = { scale: async () => SCALED };

export const SHA256 = 'a'.repeat(64);
