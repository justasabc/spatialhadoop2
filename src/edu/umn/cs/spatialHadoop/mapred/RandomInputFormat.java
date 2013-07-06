package edu.umn.cs.spatialHadoop.mapred;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

import org.apache.hadoop.mapred.InputFormat;
import org.apache.hadoop.mapred.InputSplit;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.RecordReader;
import org.apache.hadoop.mapred.Reporter;

import edu.umn.cs.spatialHadoop.core.Rectangle;
import edu.umn.cs.spatialHadoop.core.Shape;

public class RandomInputFormat<S extends Shape> implements InputFormat<Rectangle, S> {

  static class GeneratedSplit implements InputSplit {
    
    /**Index of this split*/
    int index;
    
    /**Length of this split*/
    long length;
    
    public GeneratedSplit() {}
    
    public GeneratedSplit(int index, long length) {
      super();
      this.index = index;
      this.length = length;
    }

    @Override
    public void write(DataOutput out) throws IOException {
      out.writeInt(index);
      out.writeLong(length);
    }

    @Override
    public void readFields(DataInput in) throws IOException {
      this.index = in.readInt();
      this.length = in.readLong();
    }

    @Override
    public long getLength() throws IOException {
      return 0;
    }

    @Override
    public String[] getLocations() throws IOException {
      final String[] emptyLocations = new String[0];
      return emptyLocations;
    }
  }

  
  @Override
  public InputSplit[] getSplits(JobConf job, int numSplits) throws IOException {
    long totalFileSize = job.getLong(RandomShapeGenerator.GenerationSize, 0);
    InputSplit[] splits = new GeneratedSplit[numSplits];
    for (int i = 0; i < numSplits; i++) {
      splits[i] = new GeneratedSplit(i, totalFileSize / numSplits);
    }
    return splits;
  }

  @Override
  public RecordReader<Rectangle, S> getRecordReader(InputSplit split,
      JobConf job, Reporter reporter) throws IOException {
    GeneratedSplit gsplit = (GeneratedSplit) split;
    return new RandomShapeGenerator<S>(job, gsplit);
  }


}