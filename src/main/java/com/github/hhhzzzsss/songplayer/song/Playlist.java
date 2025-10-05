package com.github.hhhzzzsss.songplayer.song;

import com.github.hhhzzzsss.songplayer.SongPlayer;
import com.github.hhhzzzsss.songplayer.Util;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class Playlist {
    public static final String INDEX_FILE_NAME = "index.json";
    private static final Gson gson = new Gson();

    public String name;
    public boolean loop = false;
    public boolean shuffle = false;

    public List<String> index;
    public List<Path> songFiles;
    public List<Song> songs = new ArrayList<>();
    public List<Integer> ordering = null;
    public int songNumber = 0;
    public boolean loaded = false;
    public ArrayList<String> songsFailedToLoad = new ArrayList<>();

    public LinkedList<Song> songQueue = new LinkedList<>();

    public Playlist(Path directory, boolean loop, boolean shuffle) {
        this.name = directory.getFileName().toString();
        this.loop = loop;
        this.shuffle = shuffle;
        this.setShuffle(this.shuffle);
        if (Files.isDirectory(directory)) {
            index = validateAndLoadIndex(directory);
            songFiles = index.stream()
                    .map(name -> directory.resolve(name))
                    .collect(Collectors.toList());
            (new PlaylistLoaderThread()).start();
        } else {
            ordering = new ArrayList<>();
            loaded = true;
        }
    }

    private class PlaylistLoaderThread extends Thread {
        @Override
        public void run() {
            for (Path file : songFiles) {
                SongLoaderThread slt = new SongLoaderThread(file);
                slt.run();
                if (slt.exception != null) {
                    songsFailedToLoad.add(file.getFileName().toString());
                } else {
                    songs.add(slt.song);
                }
            }
            // Don't run loadOrdering asynchronously to avoid concurrency issues
            SongPlayer.MC.execute(() -> {
                loadOrdering(shuffle);
                loaded = true;
            });
        }
    }

    public void setLoop(boolean loop) {
        this.loop = loop;
    }

    public void setShuffle(boolean shuffle) {
        if (this.shuffle != shuffle && loaded) {
            loadOrdering(shuffle);
        }
        this.shuffle = shuffle;
    }

    public void loadOrdering(boolean shouldShuffle) {
        songNumber = 0;
        ordering = new ArrayList<>();
        for (int i = 0; i < songs.size(); i++) {
            ordering.add(i);
        }
        if (shouldShuffle) {
            Collections.shuffle(ordering);
        }
    }

    public Song getNext() {
        if (!songQueue.isEmpty()) {
            return songQueue.poll();
        }

        
        if (songs.isEmpty()) return null;

        if (songNumber >= songs.size()) {
            if (loop) {
                loadOrdering(shuffle);
            } else {
                return null;
            }
        }

        return songs.get(ordering.get(songNumber++));
    }

    public void queueSong(Song song) {
        songQueue.add(song);
        Util.showChatMessage("§6Added song to queue: §3" + song.name);
    }

    public void queueSongNext(Song song) {
        songQueue.add(0, song);
        Util.showChatMessage("§6Playing song next: §3" + song.name);
    }

    private static List<String> validateAndLoadIndex(Path directory) {
        List<String> songNames = getSongFiles(directory)
                .map(Path::getFileName)
                .map(Path::toString)
                .collect(Collectors.toList());
        if (!Files.exists(getIndexFile(directory))) {
            saveIndexSilently(directory, songNames);
            return songNames;
        }
        else {
            try {
                List<String> index = loadIndex(directory);
                boolean modified = false;

                // Remove nonexistent songs from index
                HashSet<String> songSet = new HashSet<>(songNames);
                ListIterator<String> indexItr = index.listIterator();
                while (indexItr.hasNext()) {
                    if (!songSet.contains(indexItr.next())) {
                        indexItr.remove();
                        modified=true;
                    }
                }

                // Add songs that aren't in the index to the index
                HashSet<String> indexSet = new HashSet<>(index);
                for (String name : songNames) {
                    if (!indexSet.contains(name)) {
                        index.add(name);
                        indexSet.add(name);
                        modified = true;
                    }
                }

                if (modified) {
                    saveIndexSilently(directory, index);
                }

                return index;
            }
            catch (Exception e) {
                return songNames;
            }
        }
    }

    public static Path getIndexFile(Path directory) {
        return directory.resolve(INDEX_FILE_NAME);
    }

    public static Stream<Path> getSongFiles(Path directory) {
        try {
            Stream<Path> files = Files.list(directory);
            return files.filter(file -> !file.getFileName().toString().equals(INDEX_FILE_NAME));
        }
        catch (IOException e) {
            return null;
        }
    }

    private static List<String> loadIndex(Path directory) throws IOException {
        Path indexFile = getIndexFile(directory);
        BufferedReader reader = Files.newBufferedReader(indexFile);
        Type type = new TypeToken<ArrayList<String>>(){}.getType();
        List<String> index = gson.fromJson(reader, type);
        reader.close();
        return index;
    }

    private static void saveIndex(Path directory, List<String> index) throws IOException {
        Path indexFile = getIndexFile(directory);
        BufferedWriter writer = Files.newBufferedWriter(indexFile);
        writer.write(gson.toJson(index));
        writer.close();
    }

    private static void saveIndexSilently(Path directory, List<String> index) {
        try {
            saveIndex(directory, index);
        }
        catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static List<String> listSongs(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            throw new IOException("Playlist does not exist");
        }
        return validateAndLoadIndex(directory);
    }

    public static void createPlaylist(String playlist) throws IOException {
        Path playlistDir = Util.resolveWithIOException(SongPlayer.PLAYLISTS_DIR, playlist);
        Files.createDirectories(playlistDir);
    }

    public static void addSong(Path directory, Path songFile) throws IOException {
        if (!Files.exists(directory)) {
            throw new IOException("Playlist does not exist");
        }
        if (!Files.exists(songFile)) {
            throw new IOException("Could not find specified song");
        }

        List<String> index = validateAndLoadIndex(directory);
        if (index.contains(songFile.getFileName().toString())) {
            throw new IOException("Playlist already contains a song by this name");
        }
        Files.copy(songFile, Util.resolveWithIOException(directory, songFile.getFileName().toString()));
        index.add(songFile.getFileName().toString());
        saveIndex(directory, index);
    }

    public static void removeSong(Path directory, String songName) throws IOException {
        if (!Files.exists(directory)) {
            throw new IOException("Playlist does not exist");
        }
        Path songFile = Util.resolveWithIOException(directory, songName);
        if (!Files.exists(songFile)) {
            throw new IOException("Playlist does not contain a song by this name");
        }

        List<String> index = validateAndLoadIndex(directory);
        Files.delete(songFile);
        index.remove(songName);
        saveIndex(directory, index);
    }

    public static void deletePlaylist(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            throw new IOException("Playlist does not exist");
        }
        Files.walk(directory)
                .map(Path::toFile)
                .sorted(Comparator.reverseOrder())
                .forEach(File::delete);
    }

    // Returns old name
    public static String renameSong(Path directory, int pos, String newName) throws IOException {
        List<String> index = validateAndLoadIndex(directory);
        if (pos < 0 || pos >= index.size()) {
            throw new IOException("Index out of bounds");
        }
        Path oldPath = Util.resolveWithIOException(directory, index.get(pos));
        Files.move(oldPath, Util.resolveWithIOException(directory, newName));
        index.set(pos, newName);
        saveIndex(directory, index);
        return oldPath.getFileName().toString();
    }
}
