package io.github.leibnizhu.tinylsm

import java.util.PriorityQueue
import scala.collection.mutable.ArrayBuffer
import scala.jdk.CollectionConverters.*
import scala.util.hashing.MurmurHash3

/**
 * LSM存储相关的迭代器trait
 * 调用顺序：isValid -> key/value -> next
 *
 * @tparam K key类型
 * @tparam V value类型
 */
trait StorageIterator[K, V] {

  /**
   * 当前key
   */
  def key(): K

  /**
   * 当前值
   *
   * @return
   */
  def value(): V

  /**
   * 这里可用指可调用key() value() next()
   * 千万注意这是不同于hasNext的语义
   * 迭代器初始化之后应该是可用状态，无需调用next()方法已经指向第一个元素
   *
   * @return 是否可用
   */
  def isValid: Boolean

  /**
   * 移动游标
   */
  def next(): Unit

  /**
   * 当前迭代器的潜在活动迭代器的数量。
   */
  def numActiveIterators(): Int = 1
}

/**
 * 用于遍历一个MemTable的迭代器
 *
 * @param iterator MemTable内部数据MemTableEntry的迭代器
 */
class MemTableIterator(val iterator: Iterator[MemTableEntry])
  extends MemTableStorageIterator {
  // 记录当前迭代到的Entry
  private var currentEntry: MemTableEntry = if (iterator.hasNext) iterator.next() else null

  /**
   * 当前key
   */
  override def key(): MemTableKey = {
    currentEntry.getKey.bytes
  }

  /**
   * 当前值
   *
   * @return
   */
  override def value(): MemTableValue = {
    currentEntry.getValue
  }

  /**
   *
   * @return 是否可用（可调用key() value() next()）不同于hasNext的语义
   */
  override def isValid: Boolean = {
    currentEntry != null
  }

  /**
   * 移动游标
   */
  override def next(): Unit = {
    if (iterator.hasNext) {
      currentEntry = iterator.next()
    } else {
      currentEntry = null
    }
  }
}

/**
 * 合并多个迭代器，迭代顺序是按key升序。
 * 如果多个迭代器有相同的key，那么取最新（下标最小）的迭代器，且其他同key的迭代器迭代下一个元素
 * 在实现上，使用一个小顶堆，大小和迭代器数量相同，初始时将所有可用的迭代器入堆
 * 每次迭代（next()）时，如果堆中有相同的key的其他迭代器，只保留堆顶的(也就是保留最新的)，其他移除
 * 而堆顶是当前在用的，所以堆顶的迭代器需要迭代，同时需要重新入堆以更新其位置
 *
 * @param iterHeap 所有迭代器构成的小顶堆
 * @param curItr   当前用到的迭代器
 */
class MergeIterator(val iterHeap: PriorityQueue[HeapWrapper],
                    var curItr: Option[HeapWrapper] = None)
  extends MemTableStorageIterator {

  /**
   * 当前key
   */
  override def key(): MemTableKey = {
    curItr.map(_.key()).orNull
  }

  /**
   * 当前值
   *
   * @return
   */
  override def value(): MemTableValue = {
    curItr.map(_.itr.value()).orNull
  }

  /**
   *
   * @return 是否可用（有下一个元素）
   */
  override def isValid: Boolean = {
    curItr.map(_.isValid).getOrElse(false)
  }

  /**
   * 移动游标
   */
  override def next(): Unit = {
    val curKey = key()
    val curIter = curItr.get

    // 如果堆中有相同的key的其他迭代器，只保留堆顶的(也就是保留最新的)，其他移除
    val itersItr = iterHeap.iterator()
    val toOffer = ArrayBuffer[HeapWrapper]()
    while (itersItr.hasNext) {
      val itr: HeapWrapper = itersItr.next()
      if (curIter != itr && curKey.sameElements(itr.key())) {
        itersItr.remove()
        itr.next()
        if (itr.isValid) {
          // 不能直接offer给堆，否则会 ConcurrentModificationException
          toOffer.addOne(itr)
        }
        // 不可用的话，就不处理了，已经remove
      }
    }
    toOffer.foreach(iterHeap.offer)

    curIter.next()
    // 当前迭代器往下、更新位置，并更新当前迭代器
    if (!curIter.isValid) {
      // 当前迭代器不可用了，移出堆
      iterHeap.poll()
    } else {
      // 当前迭代器可以，继续迭代，并更新在堆的位置
      iterHeap.remove(curIter)
      iterHeap.offer(curIter)
    }
    // 更新当前的迭代器为堆顶迭代器
    curItr = Option(iterHeap.peek())
  }
}

/**
 * 包装一个MemTable迭代器，用到堆中
 * 提供了按key+MemTable层级排序的功能，实现配套方法（compareTo/hashCode/equals）
 *
 * @param index 当前MemTable迭代器的序号，越小越新，0对应未freeze的MemTable，1之后是已freeze的MemTable
 * @param itr   MemTable迭代器
 */
case class HeapWrapper(index: Int, itr: MemTableStorageIterator)
  extends Comparable[HeapWrapper] {

  def key(): MemTableKey = itr.key()

  def isValid: Boolean = itr.isValid

  def next(): Unit = itr.next()

  override def compareTo(other: HeapWrapper): Int = {
    // 先按key进行比较，同key的时候更新（index更小的）的优先
    val keyCompare = byteArrayCompare(this.itr.key(), other.itr.key())
    if (keyCompare == 0) {
      // 小的index就是更新的迭代器
      this.index - other.index
    } else {
      keyCompare
    }
  }

  override def hashCode(): Int = MurmurHash3.seqHash(Array(index) ++ itr.key())

  override def equals(other: Any): Boolean = other match
    case otherHw: HeapWrapper => otherHw.index == this.index &&
      otherHw.itr.key().sameElements(this.itr.key())
    case _ => false

  override def toString: String = if (itr.isValid)
    s"Index=$index, current: ${new String(itr.key())} => ${new String(itr.value())}})" else s"Index=$index, current invalid"
}

object MergeIterator {
  def apply(iterators: List[MemTableStorageIterator]): MergeIterator = {
    val heap = new PriorityQueue[HeapWrapper](Math.max(1, iterators.length))
    if (iterators.isEmpty) {
      new MergeIterator(heap, None)
    } else if (iterators.forall(!_.isValid)) {
      // 所有迭代器都不可用，那么当前迭代器用第一个就可以
      new MergeIterator(heap, Some(HeapWrapper(0, iterators.head)))
    } else {
      // 否则所有迭代器入堆，取堆顶作为当前迭代器
      for ((itr, index) <- iterators.zipWithIndex) {
        if (itr.isValid) {
          heap.offer(HeapWrapper(index, itr))
        }
      }
      new MergeIterator(heap, Some(heap.peek()))
    }
  }
}

/**
 * LsmIterator内部包装的迭代器类型，后续可能会变
 */
type LsmIteratorInner = MergeIterator

/**
 * 用于LSM的遍历，主要封装了已删除元素的处理逻辑
 *
 * @param innerIter LsmIteratorInner内部迭代器
 * @param endBound  遍历的key上界
 */
class LsmIterator(innerIter: LsmIteratorInner, endBound: Bound) extends MemTableStorageIterator {
  // LsmIterator本身是否可用，
  private var isSelfValid = innerIter.isValid
  // 跳过前面已删除的元素
  moveToNonDeleted()

  override def key(): MemTableKey = innerIter.key()

  override def value(): MemTableValue = innerIter.value()

  override def isValid: Boolean = isSelfValid

  override def next(): Unit = {
    innerNext()
    moveToNonDeleted()
  }

  /**
   * 包装的迭代器继续迭代
   */
  private def innerNext(): Unit = {
    innerIter.next()
    // 如果迭代器不可用则直接跳过
    if (!innerIter.isValid) {
      isSelfValid = false
      return
    }
    // 否则还要检查下上界，如果到达上界则当前LsmIterator不可用
    endBound match
      case Included(r) => isSelfValid = byteArrayCompare(key(), r) <= 0
      case Excluded(r) => isSelfValid = byteArrayCompare(key(), r) < 0
      case _ => {}
  }

  /**
   * 跳过迭代器里的空值
   */
  private def moveToNonDeleted(): Unit = {
    while (isSelfValid && innerIter.value().sameElements(DELETE_TOMBSTONE)) {
      innerNext()
    }
  }

  override def numActiveIterators(): Int = innerIter.numActiveIterators()
}

/**
 * 主要用于包装异常处理
 * TODO 优化泛型声明
 *
 * @param iter 要包装的 StorageIterator 迭代器
 * @tparam K key类型
 * @tparam V value类型
 */
class FusedIterator[K, V](val iter: StorageIterator[K, V])
  extends StorageIterator[K, V] {
  // 是否已经抛出异常
  private var errorThrown: Boolean = false

  override def key(): K = {
    if (!isValid) {
      throw new IllegalStateException("Iterator is invalid")
    }
    iter.key()
  }

  override def value(): V = {
    if (!isValid) {
      throw new IllegalStateException("Iterator is invalid")
    }
    iter.value()
  }

  override def isValid: Boolean = {
    !errorThrown && iter.isValid
  }

  override def next(): Unit = {
    // 已经发生过错误的禁止next()
    if (errorThrown) {
      throw new IllegalStateException(" This Iterator threw exception...")
    }
    // 包装的迭代器不可用时禁止next()
    if (iter.isValid) {
      try {
        iter.next()
      } catch {
        case t: Throwable => {
          errorThrown = true
          throw t
        }
      }
    }
  }

  override def numActiveIterators(): Int = iter.numActiveIterators()
}