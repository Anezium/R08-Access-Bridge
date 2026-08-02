package com.anezium.ringhealth.internal.storage;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

import com.anezium.ringhealth.StepDay;

@Entity(tableName = "step_days", indices = {
        @Index(value = {"ringId", "localDate"}, unique = true),
        @Index(value = {"localDate"})
})
public class StepDayEntity {
    @PrimaryKey(autoGenerate = true) public long id;
    @NonNull public String ringId = "";
    @NonNull public String localDate = "";
    public int steps;
    public int runningSteps;
    public int calories;
    public int distance;
    public int activitySeconds;
    public long updatedAtEpochMs;

    public StepDay toPublic() {
        return new StepDay(id, ringId, localDate, steps, runningSteps, calories, distance,
                activitySeconds, updatedAtEpochMs);
    }
}
