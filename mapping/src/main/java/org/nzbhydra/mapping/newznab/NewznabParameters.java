package org.nzbhydra.mapping.newznab;

import com.google.common.base.MoreObjects;
import lombok.Data;

import java.util.List;

@Data
public class NewznabParameters {

    private String apikey;

    private ActionAttribute t;

    private String q;

    private List<Integer> cat;

    private String rid;
    private String tvdbid;
    private String tvmazeId;
    private String traktId; //TODO implement?
    private String imdbid;
    private String tmdbid;
    private Integer season;
    private String ep;
    private String author;
    private String title;

    private Integer offset = 0;
    private Integer limit = 100;
    private Integer minage;
    private Integer maxage;
    private Integer minsize;
    private Integer maxsize;

    private String id;

    private boolean raw;
    private OutputType o;

    //Not (yet) supported
    private String genre;
    private List<String> attrs;
    private boolean extended;


    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("t", t)
                .add("q", q)
                .add("cat", cat)
                .add("imdbid", imdbid)
                .add("tmdbid", tmdbid)
                .add("rid", rid)
                .add("tvdbid", tvdbid)
                .add("taktId", traktId)
                .add("tvmazeId", tvmazeId)
                .add("season", season)
                .add("ep", ep)
                .add("author", author)
                .add("title", title)
                .add("offset", offset)
                .add("limit", limit)
                .add("maxage", maxage)
                .add("id", id)
                .add("raw", raw)
                .add("o", o)
                .add("genre", genre)
                .add("attrs", attrs)
                .add("extended", extended)
                .omitNullValues()
                .toString();
    }
}