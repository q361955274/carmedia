package com.ecarx.carmedia;

import org.json.JSONObject;

import java.io.Serializable;

/**
 * USB 本地音乐歌曲项 POJO。
 *
 * 字段映射自 MediaStore.Audio.Media 或 File 递归扫描兜底结果。
 * albumId 仅用于内部/扩展（封面抓取等），不进入 toJson()。
 */
public class UsbMusicItem implements Serializable {

    private static final long serialVersionUID = 1L;

 /** MediaStore._id，File 兜底扫描时用 System.identityHashCode 占位。 */
    public long id;
    public String title;
    public String artist;
    public String album;
 /** 时长（毫秒）。 */
    public long duration;
 /** 文件绝对路径。 */
    public String path;
 /** 所在文件夹名（父目录名，便于分组展示）。 */
    public String folderName;
 /** MediaStore.album_id。 */
    public long albumId;
 /** 嵌入封面的本地缓存路径（无封面时为 null）。 */
    public String coverPath;

    public UsbMusicItem() {
    }

    public UsbMusicItem(long id, String title, String artist, String album,
                        long duration, String path, String folderName, long albumId) {
        this.id = id;
        this.title = title;
        this.artist = artist;
        this.album = album;
        this.duration = duration;
        this.path = path;
        this.folderName = folderName;
        this.albumId = albumId;
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getArtist() {
        return artist;
    }

    public void setArtist(String artist) {
        this.artist = artist;
    }

    public String getAlbum() {
        return album;
    }

    public void setAlbum(String album) {
        this.album = album;
    }

    public long getDuration() {
        return duration;
    }

    public void setDuration(long duration) {
        this.duration = duration;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getFolderName() {
        return folderName;
    }

    public void setFolderName(String folderName) {
        this.folderName = folderName;
    }

    public long getAlbumId() {
        return albumId;
    }

    public void setAlbumId(long albumId) {
        this.albumId = albumId;
    }

 /**
 * 序列化为 JSONObject（供 JsBridge 推给前端）。
 * 字段：id/title/artist/album/duration/path/folderName（不含 albumId）。
 */
    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("id", id);
            o.put("title", title == null ? "" : title);
            o.put("artist", artist == null ? "" : artist);
            o.put("album", album == null ? "" : album);
            o.put("duration", duration);
            o.put("cover", coverPath == null ? "" : coverPath);
            o.put("path", path == null ? "" : path);
            o.put("folderName", folderName == null ? "" : folderName);
        } catch (Throwable t) {
            // JSONObject.put 不会抛受检异常，兜底吞掉
        }
        return o;
    }

    @Override
    public String toString() {
        return "UsbMusicItem{id=" + id + ", title='" + title + "', artist='" + artist
                + "', album='" + album + "', duration=" + duration
                + ", path='" + path + "', folderName='" + folderName + "', albumId=" + albumId + '}';
    }
}
