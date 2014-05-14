package scalite
import collection.mutable
import scala.tools.nsc.ast.parser.{Parsers, Scanners, Tokens}
import scala.reflect.internal.util.SourceFile
import scalite.Insert._

/**
 * Performs transformations on the Scanner's token stream inside the
 * scala compiler
 */
trait Transformer extends Parsers with Scanners with PartialParsers{ t =>
  def transform(input: Seq[ScannerData])(implicit source: SourceFile): Seq[ScannerData] = {

    // Move all newlines to the end of the previous line, rather than the start
    for(i <- input){
       if (i.token == Tokens.NEWLINE || i.token == Tokens.NEWLINES) i.offset -= 1
    }

    val colForLine: Seq[Int] = {
      val arr = new Array[Int](source.content.mkString.lines.length+1)
      for(token <- input){
        if (arr(token.line) == 0){
          arr(token.line) = token.col
        }
      }
      arr
    }

    render(input)

    val insertions = mutable.Seq.fill[List[Insert]](input.length)(Nil)

    val stack = mutable.Stack[(Int, Boolean, Boolean)]((1, false, false))
    def nextLineToken(i: Int) = input(i+1).token match{
      case Tokens.NEWLINE | Tokens.NEWLINES => Some(i+2)
      case _ => Some(i+1)
    }

    for(i <- 0 until input.length - 1){
      val curr = input(i)
      println(i + "\tloop\t" + stack.top + "\t" + curr.col + "\t" + token2string(curr.token))
      for(next <- nextLineToken(i)) {
        if (input(next).token != Tokens.CASE) while(input(next).col < stack.top._1) {
          if (stack.top._2) insertions(i) ::= DeleteDo
          insertions(i) ::= RBrace
          if (stack.top._3)insertions(i) ::= RParen
          println{"POP STACK " + stack.pop()}
        }
      }

      val stream = input.toStream.drop(i)

      for{
        f <- modifierFor.lift(stream.takeWhile(_.line == stream(0).line).map(_.token))
        tokens = f(new PartialParser(input.toIterator.drop(i), colForLine)(source))
        lastOpt <- tokens.lastOption
        last = input(i + lastOpt._1)
        next <- nextLineToken(i + tokens.last._1 - 1)
        if input(next).line > input(i).line
        if input(next).col > colForLine(input(i).line) || input(next).token == Tokens.CASE
        (offset, token) <- tokens
      }{
        token match{
          case t: LBraceStack => t.baseIndent = input(next).col
          case t: LBraceCaseStack => t.baseIndent = input(next).col
          case t: LBraceDoStack => t.baseIndent = input(next).col
          case t: LParenDoStack => t.baseIndent = input(next).col
          case _ =>
        }
        println("Adding... " + token)
        insertions(i + offset - 1) ::= token
      }

      insertions(i).collect{
        case LBraceStack(baseIndent) => stack.push((baseIndent, false, false))
        case LBraceCaseStack(baseIndent) => stack.push((baseIndent + 1, false, false))
        case LBraceDoStack(baseIndent) => stack.push((baseIndent, true, false))
        case LParenDoStack(baseIndent) => stack.push((baseIndent, true, true))
      }
    }
    while(stack.length > 1){
      insertions(insertions.length - 2) ::= RBrace
      println{"POP STACK " + stack.pop()}
    }
    insertions.foreach(println)
    val merged = mutable.Buffer.empty[ScannerData]
    var deleteDo = false
    for(i <- 0 until input.length){
      input(i).token match {
        case Tokens.DO if deleteDo => deleteDo = false
        case Tokens.NEWLINE => merged.append(input(i))
        case _ =>
          merged.append(input(i))
          deleteDo = false
      }

      insertions(i).reverse.foreach{
        case LParenDoStack(_) =>
          for(i <- Seq(Tokens.LPAREN, Tokens.LBRACE)) {
            merged.append(copyData(merged.last, _.token = i))
          }
        case LBraceDoStack(_) | LBraceCaseStack(_) | LBraceStack(_) | LBrace =>
          merged.append(copyData(merged.last, _.token = Tokens.LBRACE))

        case RBrace => merged.append(copyData(merged.last, _.token = Tokens.RBRACE))
        case RParen => merged.append(copyData(merged.last, _.token = Tokens.RPAREN))
        case LParen => merged.append(copyData(merged.last, _.token = Tokens.LPAREN))
        case DeleteDo => deleteDo = true
      }
    }


    render(merged)

    merged
  }
}
