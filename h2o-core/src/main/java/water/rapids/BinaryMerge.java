package water.rapids;

// Since we have a single key field in H2O (different to data.table), bmerge() becomes a lot simpler (no
// need for recursion through join columns) with a downside of transfer-cost should we not need all the key.

import water.DKV;
import water.DTask;
import water.H2O;
import water.fvec.Frame;
import static water.rapids.SingleThreadRadixOrder.getMSBHeaderKey;
import water.util.ArrayUtils;
import water.util.Log;

public class BinaryMerge extends DTask<BinaryMerge> {
  long _retFirst[];  // The row number of the first right table's index key that matches
  long _retLen[];    // How many rows does it match to?
  byte _leftKey[/*n2GB*/][/*i mod 2GB * _keySize*/];
  byte _rightKey[][];
  long _leftOrder[/*n2GB*/][/*i mod 2GB * _keySize*/];
  long _rightOrder[][];
  boolean _allLen1 = true;
  int _leftFieldSizes[], _rightFieldSizes[];  // the widths of each column in the key
  int _leftKeyNCol, _rightKeyNCol;  // the number of columns in the key i.e. length of _leftFieldSizes and _rightFieldSizes
  int _leftKeySize, _rightKeySize;   // the total width in bytes of the key, sum of field sizes
  int _numJoinCols;
  int _leftNodeIdx;
  long _leftN, _rightN;
  long _leftBatchSize, _rightBatchSize;

  BinaryMerge(Frame leftFrame, Frame rightFrame, int leftMSB, int rightMSB, int leftNodeIdx, int leftFieldSizes[], int rightFieldSizes[]) {   // In X[Y], 'left'=i and 'right'=x
    SingleThreadRadixOrder.MSBHeader leftMSBHeader = DKV.getGet(getMSBHeaderKey(leftFrame, leftMSB));
    SingleThreadRadixOrder.MSBHeader rightMSBHeader = DKV.getGet(getMSBHeaderKey(rightFrame, rightMSB));
    if (leftMSBHeader == null || rightMSBHeader == null) return;
    _leftBatchSize = leftMSBHeader._batchSize;
    _rightBatchSize = rightMSBHeader._batchSize;

    // get left batches
    _leftKey = new byte[leftMSBHeader._nBatch][];
    _leftOrder = new long[leftMSBHeader._nBatch][];
    for (int b=0;b<leftMSBHeader._nBatch; ++b) {
      MoveByFirstByte.OXbatch oxLeft = DKV.getGet(MoveByFirstByte.getOXbatchKey(leftFrame._key, leftMSB, leftNodeIdx, b));
      _leftKey[b] = oxLeft._x;
      _leftOrder[b] = oxLeft._o;
    }
    _leftN = leftMSBHeader._numRows;

    // get right batches
    _rightKey = new byte[rightMSBHeader._nBatch][];
    _rightOrder = new long[rightMSBHeader._nBatch][];
    for (int b=0;b<rightMSBHeader._nBatch; ++b) {
      MoveByFirstByte.OXbatch oxRight = DKV.getGet(MoveByFirstByte.getOXbatchKey(rightFrame._key, rightMSB, H2O.SELF.index(), b));
      _rightKey[b] = oxRight._x;
      _rightOrder[b] = oxRight._o;
    }
    _rightN = rightMSBHeader._numRows;

    _retFirst = new long[(int)_leftN];
    _retLen = new long[(int)_leftN];
    _leftNodeIdx = leftNodeIdx;
    _leftFieldSizes = leftFieldSizes;
    _rightFieldSizes = rightFieldSizes;
    _leftKeyNCol = _leftFieldSizes.length;
    _rightKeyNCol = _rightFieldSizes.length;
    _leftKeySize = ArrayUtils.sum(leftFieldSizes);
    _rightKeySize = ArrayUtils.sum(rightFieldSizes);
    _numJoinCols = Math.min(_leftKeyNCol, _rightKeyNCol);
  }

  @Override
  protected void compute2() {
    if (_leftN != 0 && _rightN != 0) {
      // do the work
      bmerge_r(-1, _leftN, -1, _rightN);

      //put stuff into DKV
    }

    //null out members before returning to calling node
    tryComplete();
  }


  private int keycmp(byte x[][], long xi, byte y[][], long yi) {   // TO DO - faster way closer to CPU like batches of long compare, maybe.
    byte xByte=0, yByte=0;
    xi *= _leftKeySize;
    yi *= _rightKeySize;   // x[] and y[] are len keys.
    // TO DO: rationalize x and y being chunked into 2GB pieces.  Take x[0][] and y[0][] outside loop / function
    // TO DO: switch to use keycmp_sameShape() for common case of all(leftFieldSizes == rightFieldSizes), although, skipping to current column will
    //        help save repeating redundant work and saving the outer for() loop and one if() may not be worth it.
    int i=0, xlen=0, ylen=0, diff=0;
    while (i<_numJoinCols && xlen==0) {    // TO DO: pass i in to start at a later key column, when known
      xlen = _leftFieldSizes[i];
      ylen = _rightFieldSizes[i];
      if (xlen!=ylen) {
        while (xlen>ylen && x[0][(int)xi]==0) { xi++; xlen--; }
        while (ylen>xlen && y[0][(int)yi]==0) { yi++; ylen--; }
        if (xlen!=ylen) return (xlen - ylen);
      }
      while (xlen>0 && (xByte=x[0][(int)xi])==(yByte=y[0][(int)yi])) { xi++; yi++; xlen--; }
      i++;
    }
    return (xByte & 0xFF) - (yByte & 0xFF);
    // Same return value as strcmp in C. <0 => xi<yi
  }

  private void bmerge_r(long lLowIn, long lUppIn, long rLowIn, long rUppIn) {
    // TO DO: parallel each of the 256 bins
    long lLow = lLowIn, lUpp = lUppIn, rLow = rLowIn, rUpp = rUppIn;
    long mid, tmpLow, tmpUpp;
    long lr = lLow + (lUpp - lLow) / 2;   // i.e. (lLow+lUpp)/2 but being robust to one day in the future someone somewhere overflowing long; e.g. 32 exabytes of 1-column ints
    while (rLow < rUpp - 1) {
      mid = rLow + (rUpp - rLow) / 2;
      int cmp = keycmp(_leftKey, lr, _rightKey, mid);  // -1, 0 or 1, like strcmp
      if (cmp < 0) {
        rUpp = mid;
      } else if (cmp > 0) {
        rLow = mid;
      } else { // rKey == lKey including NA == NA
        // branch mid to find start and end of this group in this column
        // TO DO?: not if mult=first|last and col<ncol-1
        tmpLow = mid;
        tmpUpp = mid;
        while (tmpLow < rUpp - 1) {
          mid = tmpLow + (rUpp - tmpLow) / 2;
          if (keycmp(_leftKey, lr, _rightKey, mid) == 0) tmpLow = mid;
          else rUpp = mid;
        }
        while (rLow < tmpUpp - 1) {
          mid = rLow + (tmpUpp - rLow) / 2;
          if (keycmp(_leftKey, lr, _rightKey, mid) == 0) tmpUpp = mid;
          else rLow = mid;
        }
        break;
      }
    }
    // rLow and rUpp now surround the group in the right table.

    // The left table key may (unusually, and not recommended, but sometimes needed) be duplicated.
    // Linear search outwards from left row. Most commonly, the first test shows this left key is unique.
    // This saves i) re-finding the matching rows in the right for all the dup'd left and ii) recursive bounds logic gets awkward if other left rows can find the same right rows
    // Related to 'allow.cartesian' in data.table.
    // TO DO:  if index stores attribute that it is unique then we don't need this step. However, each of these while()s would run at most once in that case, which may not be worth optimizing.
    tmpLow = lr + 1;
    while (tmpLow<lUpp && keycmp(_leftKey, tmpLow, _leftKey, lr)==0) tmpLow++;
    lUpp = tmpLow;
    tmpUpp = lr - 1;
    while (tmpUpp>lLow && keycmp(_leftKey, tmpUpp, _leftKey, lr)==0) tmpUpp--;
    lLow = tmpUpp;
    // lLow and lUpp now surround the group in the left table.  If left key is unique then lLow==lr-1 and lUpp==lr+1.

    long len = rUpp - rLow - 1;  // if value found, rLow and rUpp surround it, unlike standard binary search where rLow falls on it
    if (len > 0) {
      if (len > 1) _allLen1 = false;
      for (long j = lLow + 1; j < lUpp; j++) {   // usually iterates once only for j=lr, but more than once if there are dup keys in left table
        _retFirst[(int) j] = rLow + 1;
        _retLen[(int) j] = len;
        StringBuilder sb = new StringBuilder();
        sb.append("Left row " + _leftOrder[(int)(j/_leftBatchSize)][(int)(j%_leftBatchSize)] + " matches to " + _retLen[(int)j] + " right rows: ");
        long a = _retFirst[(int) j];
        for (int i=0; i<_retLen[(int)j]; ++i) {
          long loc = a+i;
          sb.append(_rightOrder[(int)(loc / _rightBatchSize)][(int)(loc % _rightBatchSize)] + " ");
        }
        Log.info(sb);
      }
    }
    // TO DO: check assumption that retFirst and retLength are initialized to 0, for case of no match
    // Now branch (and TO DO in parallel) to merge below and merge above
    if (lLow > lLowIn && rLow > rLowIn)
      bmerge_r(lLowIn, lLow + 1, rLowIn, rLow+1);
    if (lUpp < lUppIn && rUpp < rUppIn)
      bmerge_r(lUpp-1, lUppIn, rUpp-1, rUppIn);
  }
}