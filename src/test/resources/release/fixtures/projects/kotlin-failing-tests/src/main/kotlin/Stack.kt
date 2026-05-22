class Stack<T>(private val items: MutableList<T> = mutableListOf()) {
    fun push(item: T) { items.add(item) }
    fun pop(): T = items.removeAt(items.size - 1)
    fun peek(): T = items[0]  // bug: should be items[items.size - 1]
    fun isEmpty(): Boolean = items.isEmpty()
    fun size(): Int = items.size
}
