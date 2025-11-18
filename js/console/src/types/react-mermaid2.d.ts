declare module 'react-mermaid2' {
  import { ComponentType } from 'react';

  interface MermaidProps {
    chart?: string;
    config?: any;
    className?: string;
    style?: React.CSSProperties;
  }

  const Mermaid: ComponentType<MermaidProps>;
  export default Mermaid;
}