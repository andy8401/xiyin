// Map the many web-API record shapes onto the MyGo Music data model.
import { SITE, asId, cleanText, coverURL, positiveNumber } from "./utils.js";

export function toShow(row, extra = {}) {
  const record = row && typeof row === "object" ? row : {};
  const id = asId(record.albumId ?? record.id);
  return {
    id,
    name: cleanText(record.albumTitle || record.title || record.name || record.albumName) || "未命名专辑",
    imageUrl: coverURL(record.cover || record.coverPath || record.albumCoverPath || record.coverLarge || record.albumCover || record.imgUrl),
    description: cleanText(record.richIntro || record.intro || record.description || record.shortIntro || record.albumIntro),
    publisher: cleanText(record.anchorName || record.nickname || record.albumUserNickName || record.userName || record.anchor?.anchorNickName),
    episodeCount: Math.floor(positiveNumber(record.trackTotalCount ?? record.trackCount ?? record.tracksCount ?? record.albumTrackCount)),
    subscribed: Boolean(record.isSubscribe || record.subscribed),
    ...extra,
  };
}

export function toTrack(row, show = {}) {
  const record = row && typeof row === "object" ? row : {};
  const id = asId(record.trackId ?? record.id ?? record.childId);
  const showId = record.albumId ?? record.itemId ?? show.id ?? show.albumId;
  const author = record.anchorId ?? record.anchorUid ?? record.uid;
  const duration = positiveNumber(record.duration ?? record.length);
  const track = {
    id,
    name: cleanText(record.trackName || record.title || record.name || record.childTitle) || "未命名单集",
    artists: [{ id: author ? asId(author) : "", name: cleanText(record.nickname || record.anchorName || record.userName || show.publisher) }],
    album: {
      id: showId ? asId(showId) : "",
      name: cleanText(record.albumName || record.albumTitle || record.itemTitle || show.name || show.title),
      imageUrl: coverURL(record.trackCoverPath || record.coverPath || record.coverLarge || record.albumCoverPath || record.itemCoverUrl || show.imageUrl),
    },
    duration: Math.round(duration * 1000),
    url: `${SITE}/sound/${id}`,
    description: cleanText(record.intro || record.richIntro || record.description),
    unavailable: record.isNoCopyright === true || record.isPublic === false || (record.isPaid === true && record.isAuthorized === false && record.isFree !== true),
  };
  if (showId) track.showId = asId(showId);
  if (positiveNumber(record.createdAt)) track.published = new Date(Number(record.createdAt)).toISOString().slice(0, 10);
  else if (typeof record.createTime === "string" && record.createTime) track.published = record.createTime;
  return track;
}
