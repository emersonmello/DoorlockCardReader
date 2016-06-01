package uk.ac.ncl.cs.mello.doorlockcardreader;

/**
 * Created by mello on 01/06/16.
 */
public enum DoorProtocol {
    HELLO((short) 1, "1"),
    READY((short) 2, "2"),
    WAIT((short) 3, "3"),
    DONE((short) 4, "4");

    private final short id;
    private final String desc;

    DoorProtocol(final short id, final String desc){
        this.id = id;
        this.desc = desc;
    }

    public short getId() {
        return id;
    }

    public String getDesc() {
        return desc;
    }
}
