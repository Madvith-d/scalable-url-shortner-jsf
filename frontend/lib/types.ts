export type ShortUrl = {
  id: number;
  shortCode: string;
  shortUrl: string;
  originalUrl: string;
  createdAt: string;
  expiresAt: string | null;
  active: boolean;
  activatesAt?: string | null;
  maxClicks?: number | null;
  clickCount?: number;
};

export type UrlPage = {
  content: ShortUrl[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
};

export type AuthResponse = {
  accessToken: string;
  tokenType: string;
  expiresIn: number;
  email: string;
};

export type Session = { accessToken: string; email: string; expiresAt: number };
export type Bucket = { label: string; clicks: number };
export type GeographyBy = "country" | "city";
export type GeographyAnalytics = { totalClicks: number; buckets: Bucket[] };
export type Analytics = {
  totalClicks: number;
  clicksOverTime: { date: string; clicks: number }[];
  referrers: Bucket[];
  devices: Bucket[];
  geography: Bucket[];
};
export type Draft = { originalUrl: string; customAlias: string; expiresAt: string; activatesAt?: string; maxClicks?: string };
