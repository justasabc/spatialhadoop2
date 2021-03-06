package edu.umn.cs.spatialHadoop.mapred;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Array;
import java.util.List;
import java.util.Stack;

import javax.swing.tree.DefaultMutableTreeNode;

import ncsa.hdf.object.Attribute;
import ncsa.hdf.object.Dataset;
import ncsa.hdf.object.FileFormat;
import ncsa.hdf.object.Group;
import ncsa.hdf.object.HObject;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.mapred.FileSplit;
import org.apache.hadoop.mapred.RecordReader;

import edu.umn.cs.spatialHadoop.core.NASADataset;
import edu.umn.cs.spatialHadoop.core.NASAPoint;

/**
 * Reads a specific dataset from an HDF file. Upon instantiation, the portion
 * of the HDF file being read is copied to a local directory. After that, the
 * HDF Java library is used to access the file.
 * This class is designed in specific to read an HDF file provided by NASA
 * in their MODIS datasets. It assumes a certain structure and HDF file format
 * and parses the file accordingly.
 * @author Ahmed Eldawy
 *
 */
public class HDFRecordReader implements RecordReader<NASADataset, NASAPoint> {
  private static final Log LOG = LogFactory.getLog(HDFRecordReader.class);

  /** The HDF file being read*/
  private FileFormat hdfFile;
  
  /**Information about the dataset being read*/
  private NASADataset nasaDataset;
  
  /**Array of values in the dataset being read*/
  private Object dataArray;
  
  /**Position to read next in the data array*/
  private int position;
  
  /**The default value that indicates that a number is not set*/
  private int fillValue;
  
  /**Whether or not to skip fill value when returning values in a dataset*/
  private boolean skipFillValue;
  

  /**
   * Initializes the HDF reader to read a specific dataset from the given HDF
   * file.
   * @param job - Job configuration
   * @param split - A file split pointing to the HDF file
   * @param datasetName - Name of the dataset to read (case insensitive)
   * @throws Exception
   */
  public HDFRecordReader(Configuration job, FileSplit split, String datasetName, boolean skipFillValue) {
    try {
      this.skipFillValue = skipFillValue;
      init(job, split, datasetName);
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  /**
   * Initializes the reader to read a specific dataset from the given HDF file.
   * It open the HDF file and retrieves the dataset of interest into memory.
   * After this, the values are retrieved one by one from the dataset as
   * {@link #next(Dataset, NASAPoint)} is called.
   * @param job
   * @param split
   * @param datasetName
   * @throws Exception
   */
  private void init(Configuration job, FileSplit split, String datasetName) throws Exception {
    // HDF library can only deal with local files. So, we need to copy the file
    // to a local temporary directory before using the HDF Java library
    String localFile = copyFileSplit(job, split);
    
    // retrieve an instance of H4File
    FileFormat fileFormat = FileFormat.getFileFormat(FileFormat.FILE_TYPE_HDF4);
    
    hdfFile = fileFormat.createInstance(localFile, FileFormat.READ);
    
    // open the file and retrieve the file structure
    hdfFile.open();
    
    // Retrieve the root of the HDF file structure
    Group root =
        (Group)((DefaultMutableTreeNode)hdfFile.getRootNode()).getUserObject();
    
    // Search for the datset of interest in file
    Stack<Group> groups2bSearched = new Stack<Group>();
    groups2bSearched.add(root);
    
    Dataset dataset = null;
    
    while (!groups2bSearched.isEmpty()) {
      Group top = groups2bSearched.pop();
      List<HObject> memberList = top.getMemberList();
      
      for (HObject member : memberList) {
        if (member instanceof Group) {
          groups2bSearched.add((Group) member);
        } else if (member instanceof Dataset &&
            member.getName().equalsIgnoreCase(datasetName)) {
          dataset = (Dataset) member;
          break;
        }
      }
    }
    
    if (dataset == null) {
      LOG.warn("Dataset "+datasetName+" not found in file "+split.getPath());
      return;
    }

    nasaDataset = new NASADataset(root);
    nasaDataset.datasetName = datasetName;
    List<Attribute> attrs = dataset.getMetadata();
    String fillValueStr = null;
    for (Attribute attr : attrs) {
      if (attr.getName().equals("_FillValue")) {
        fillValueStr = Array.get(attr.getValue(), 0).toString();
      }
    }
    if (fillValueStr == null) {
      this.skipFillValue = false;
    } else {
      this.fillValue = Integer.parseInt(fillValueStr);
    }
    
    dataArray = dataset.read();
    
    // No longer need the HDF file
    hdfFile.close();
  }

  /**
   * Copies a part of a file from a remote file system (e.g., HDFS) to a local
   * file. Returns a path to a local temporary file.
   * @param conf
   * @param split
   * @return
   * @throws IOException 
   */
  static String copyFileSplit(Configuration conf, FileSplit split) throws IOException {
    // Open input file for read
    FileSystem fs = split.getPath().getFileSystem(conf);
    FSDataInputStream in = fs.open(split.getPath());
    in.seek(split.getStart());
    
    // Prepare output file for write
    File tempFile = File.createTempFile(split.getPath().getName(), "hdf");
    OutputStream out = new FileOutputStream(tempFile);
    
    // A buffer used between source and destination
    byte[] buffer = new byte[1024*1024];
    // Get file length. Don't depend on split.getLength as we don't set it in
    // HDFInputFormat for performance reasons
    long length = fs.getFileStatus(split.getPath()).getLen();
    while (length > 0) {
      int numBytesRead = in.read(buffer, 0, (int)Math.min(length, buffer.length));
      out.write(buffer, 0, numBytesRead);
      length -= numBytesRead;
    }
    
    in.close();
    out.close();
    return tempFile.getAbsolutePath();
  }

  @Override
  public boolean next(NASADataset key, NASAPoint point) throws IOException {
    if (dataArray == null)
      return false;
    // Key doesn't need to be changed because all points in the same dataset
    // have the same key
    while (position < Array.getLength(dataArray)) {
      // TODO set the x and y to longitude and latitude by doing the correct projection
      int row = position / nasaDataset.resolution;
      int col = position % nasaDataset.resolution;
      point.x = (nasaDataset.mbr.x1 * col +
          nasaDataset.mbr.x2 * (nasaDataset.resolution - col))
          / nasaDataset.resolution;
      point.y = (nasaDataset.mbr.y1 * row +
          nasaDataset.mbr.y2 * (nasaDataset.resolution - row))
          / nasaDataset.resolution;
      
      // Read next value
      Object value = Array.get(dataArray, position);
      if (value instanceof Integer) {
        point.value = (Integer) value;
      } else if (value instanceof Short) {
        point.value = (Short) value;
      } else if (value instanceof Byte) {
        point.value = (Byte) value;
      } else {
        throw new RuntimeException("Cannot read a value of type "+value.getClass());
      }
      position++;
      if (!skipFillValue || point.value != fillValue)
        return true;
    }
    return false;
  }

  @Override
  public NASADataset createKey() {
    return nasaDataset;
  }

  @Override
  public NASAPoint createValue() {
    return new NASAPoint();
  }

  @Override
  public long getPos() throws IOException {
    return position;
  }

  @Override
  public void close() throws IOException {
    // Nothing to do. The HDF file is already closed
  }

  @Override
  public float getProgress() throws IOException {
    return dataArray == null ? 0 : (float)position / Array.getLength(dataArray);
  }

  public static void main(String[] args) throws Exception {
    FileSplit hdfFileSplit = new FileSplit(new Path(
        "MOD11A1.A2007002.h23v07.004.2007004095814.hdf"), 0, 23613446,
        new String[] {});
    HDFRecordReader reader = new HDFRecordReader(new Configuration(),
        hdfFileSplit, "LST_Night_1km", true);
    
    NASADataset dataset = reader.createKey();
    NASAPoint point = reader.createValue();
    
    System.out.println(dataset);
    
    int count = 0;
    while (reader.next(dataset, point)) {
      count++;
    }
    
    System.out.println("Read "+count+" values");
    
    reader.close();
  }
}
