import { createFleet } from '../../../src/fleet';

export { opacityAt, positionAt, type Craft, type FeedRow } from '../../../src/fleet';

const fleet = createFleet();

export const craftList = fleet.craftList;
export const craftByHex = fleet.craftByHex;
export const notePosition = fleet.notePosition;
export const trackOf = fleet.trackOf;
export const replaceAll = fleet.replaceAll;
export const applyFeedBody = fleet.applyFeedBody;
