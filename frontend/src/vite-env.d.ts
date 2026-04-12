/// <reference types="vite/client" />

declare module "*.module.css" {
  const classes: { readonly [key: string]: string };
  export default classes;
}

declare module "cytoscape-dagre" {
  const ext: cytoscape.Ext;
  export default ext;
}

declare module "cytoscape-cose-bilkent" {
  const ext: cytoscape.Ext;
  export default ext;
}

declare module "cytoscape-edgehandles" {
  const ext: cytoscape.Ext;
  export default ext;
}

declare module "cytoscape-navigator" {
  const ext: cytoscape.Ext;
  export default ext;
}
