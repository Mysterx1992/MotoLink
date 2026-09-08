package it.motolink.vogenavbridge;

public final class NavModel {
    public int routeRemainDistM;
    public int routeRemainTimeSec;
    public long arriveRemainSec;
    public int curRoadRemainDistM;
    public String curRoadName = "";
    public int nextRoadDirection;
    public String nextRoadName = "";
    public int roadFlag;
    public int naviOnOff;
    public int annularDegrees;
    public boolean realDistance;
    public String sourceTitle = "";
    public String sourceSubText = "";

    public String summary() {
        return "dir=" + nextRoadDirection
                + " curDist=" + curRoadRemainDistM
                + " routeDist=" + routeRemainDistM
                + " routeTime=" + routeRemainTimeSec
                + " roadFlag=" + roadFlag
                + " annular=" + annularDegrees
                + " current='" + curRoadName + "'"
                + " next='" + nextRoadName + "'"
                + " realDistance=" + realDistance;
    }
}
