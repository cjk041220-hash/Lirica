import type { Metadata, Viewport } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "映海 - 小汐在这里陪你",
  description: "希望你一个人在香港的时候，也不会觉得孤单。",
  manifest: "/manifest.json",
  appleWebApp: {
    capable: true,
    statusBarStyle: "black-translucent",
    title: "小汐",
  },
};

export const viewport: Viewport = {
  themeColor: "#88B5B5",
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="zh-CN" className="h-full antialiased">
      <head>
        <link rel="apple-touch-icon" href="/icon-192.png" />
      </head>
      <body className="min-h-full bg-warm-paper text-ink-tea" suppressHydrationWarning>
        {children}
      </body>
    </html>
  );
}
