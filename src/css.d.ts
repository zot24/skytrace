declare module '*.css';

declare module '*?url' {
  const href: string;
  export default href;
}
