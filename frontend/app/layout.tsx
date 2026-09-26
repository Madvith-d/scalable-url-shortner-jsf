import type { Metadata } from "next";
import "@fontsource/space-grotesk/500.css";
import "@fontsource/space-grotesk/600.css";
import "@fontsource/space-grotesk/700.css";
import "@fontsource/ibm-plex-sans/400.css";
import "@fontsource/ibm-plex-sans/500.css";
import "@fontsource/ibm-plex-sans/600.css";
import "./globals.css";
import { AuthProvider } from "@/components/auth-provider";
import { Shell } from "@/components/shell";
import { ThemeProvider } from "@/components/theme-provider";
import { themeBootstrapScript } from "@/lib/theme";

export const metadata: Metadata = {
  title: { default: "Shortify — URL shortener", template: "%s | Shortify" },
  description: "Shorten links, manage their availability, and view click analytics.",
};

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="en" suppressHydrationWarning>
      <head><script dangerouslySetInnerHTML={{ __html: themeBootstrapScript }} /></head>
      <body>
        <ThemeProvider>
          <AuthProvider><Shell>{children}</Shell></AuthProvider>
        </ThemeProvider>
      </body>
    </html>
  );
}
